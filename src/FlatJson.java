import java.util.LinkedHashMap;
import java.util.Map;

/** Разбирает плоский JSON-объект конфигурации. */
public final class FlatJson {
    private static final int FIRST_PRINTABLE = 0x20;
    private static final int UNICODE_LENGTH = 4;
    private static final int HEX_RADIX = 16;
    private static final int NOT_FOUND = -1;
    private static final String HEX_DIGITS = "0123456789abcdef";
    private static final String ESCAPE_CODES = "\"\\/bfnrt";
    private static final String ESCAPE_VALUES = "\"\\/\b\f\n\r\t";

    public record NumberToken(String text) {}

    private final String text;
    private int position;

    private FlatJson(String text) {
        this.text = text;
    }

    /**
     * @param text JSON-объект
     * @return параметры конфигурации
     * @throws IllegalArgumentException при неправильном JSON
     */
    public static Map<String, Object> parse(String text) {
        if (text == null) throw new IllegalArgumentException("JSON: текст не задан");
        return new FlatJson(text).object();
    }

    private Map<String, Object> object() {
        Map<String, Object> result = new LinkedHashMap<>();
        expect('{');
        if (!take('}')) {
            do {
                whitespace();
                if (peek() != '"') throw error("ожидается имя параметра в кавычках");
                String key = string();
                expect(':');
                Object value = value();
                if (result.containsKey(key)) throw error("повторяющийся параметр: " + key);
                result.put(key, value);
            } while (take(','));
            expect('}');
        }
        whitespace();
        if (position != text.length()) throw error("лишние символы после объекта");
        return result;
    }

    private Object value() {
        whitespace();
        char character = peek();
        if (character == '"') return string();
        if (character == '-' || digit(character)) return number();
        switch (character) {
            case 't': literal("true"); return true;
            case 'f': literal("false"); return false;
            case 'n': literal("null"); return null;
            default: throw error("ожидается строка, число, true, false или null");
        }
    }

    private String string() {
        expect('"');
        StringBuilder result = new StringBuilder();
        while (position < text.length()) {
            char character = text.charAt(position++);
            if (character == '"') return result.toString();
            if (character < FIRST_PRINTABLE) throw error("управляющий символ внутри строки");
            result.append(character == '\\' ? escapedCharacter() : character);
        }
        throw error("незакрытая строка");
    }

    private char escapedCharacter() {
        if (position == text.length()) throw error("незавершённая escape-последовательность");
        char code = text.charAt(position++);
        if (code == 'u') return unicodeCharacter();
        int index = ESCAPE_CODES.indexOf(code);
        if (index == NOT_FOUND) throw error("неизвестная escape-последовательность");
        return ESCAPE_VALUES.charAt(index);
    }

    private char unicodeCharacter() {
        int code = 0;
        for (int index = 0; index < UNICODE_LENGTH; index++) {
            int hexDigit = HEX_DIGITS.indexOf(Character.toLowerCase(peek()));
            if (hexDigit == NOT_FOUND) throw error("ожидаются четыре HEX-цифры после escape u");
            position++;
            code = code * HEX_RADIX + hexDigit;
        }
        return (char) code;
    }

    private NumberToken number() {
        int start = position;
        if (peek() == '-') position++;
        integerPart();
        if (peek() == '.') {
            position++;
            requiredDigits();
        }
        exponentPart();
        return new NumberToken(text.substring(start, position));
    }

    private void integerPart() {
        if (peek() == '0') {
            position++;
            return;
        }
        if (peek() < '1' || peek() > '9') throw error("некорректное число");
        digits();
    }

    private void exponentPart() {
        if (peek() != 'e' && peek() != 'E') return;
        position++;
        if (peek() == '+' || peek() == '-') position++;
        requiredDigits();
    }

    private void requiredDigits() {
        if (!digit(peek())) throw error("ожидается цифра");
        digits();
    }

    private void digits() {
        while (digit(peek())) position++;
    }

    private static boolean digit(char character) {
        return character >= '0' && character <= '9';
    }

    private void literal(String expected) {
        if (!text.startsWith(expected, position)) throw error("ожидается " + expected);
        position += expected.length();
    }

    private char peek() {
        return position < text.length() ? text.charAt(position) : '\0';
    }

    private void whitespace() {
        while (position < text.length() && " \t\r\n".indexOf(peek()) != NOT_FOUND) position++;
    }

    private boolean take(char character) {
        whitespace();
        if (peek() != character) return false;
        position++;
        return true;
    }

    private void expect(char character) {
        if (!take(character)) throw error("ожидается символ '" + character + "'");
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("JSON, позиция " + (position + 1) + ": " + message);
    }
}