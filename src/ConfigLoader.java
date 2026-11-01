import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Разбирает и проверяет конфигурацию программы.
 */
public final class ConfigLoader {
    private static final int UNDEFINED_PORT = -1;
    private static final int MAX_PORT = 65535;

    private static final String INVALID_URL =
            "repository: требуется HTTP/HTTPS URL с именем сервера, "
                    + "без учётных данных и фрагмента";

    private static final Set<String> KEYS = Set.of(
            "packageName",
            "repository",
            "testMode",
            "asciiTree",
            "maxDepth",
            "filter"
    );

    private ConfigLoader() {
    }

    /**
     * Возвращает проверенные настройки из JSON.
     *
     * @param json текст конфигурации
     * @param configDirectory папка конфигурационного файла
     * @return проверенные настройки
     * @throws IllegalArgumentException если конфигурация неправильная
     * @throws SecurityException если операция запрещена средой выполнения
     */
    public static Config load(String json, Path configDirectory) {
        Map<String, Object> values = FlatJson.parse(json);
        validateKeys(values);

        String packageName = readPackageName(values);
        String repository = readRepository(values);
        boolean testMode = readBoolean(values, "testMode");
        boolean asciiTree = readBoolean(values, "asciiTree");
        int maxDepth = readMaxDepth(values);
        String filter = readString(values, "filter");

        validateRepository(repository, testMode, configDirectory);

        return new Config(
                packageName, repository, testMode,
                asciiTree, maxDepth, filter
        );
    }

    private static void validateKeys(Map<String, Object> values) {
        for (String key : values.keySet()) {
            require(KEYS.contains(key), "Неизвестный параметр: " + key);
        }

        for (String key : KEYS.stream().sorted().toList()) {
            require(
                    values.containsKey(key),
                    "Отсутствует обязательный параметр: " + key
            );
        }
    }

    private static String readPackageName(Map<String, Object> values) {
        String name = readString(values, "packageName");

        require(
                !name.isBlank(),
                "packageName: имя пакета не должно быть пустым"
        );
        require(
                name.codePoints().noneMatch(ConfigLoader::isForbiddenCharacter),
                "packageName: пробельные и управляющие символы запрещены"
        );

        return name;
    }

    private static boolean isForbiddenCharacter(int character) {
        return Character.isWhitespace(character)
                || Character.isSpaceChar(character)
                || Character.isISOControl(character);
    }

    private static String readRepository(Map<String, Object> values) {
        String repository = readString(values, "repository");

        require(
                !repository.isBlank(),
                "repository: источник не должен быть пустым"
        );

        return repository;
    }

    private static int readMaxDepth(Map<String, Object> values) {
        Object depth = values.get("maxDepth");

        require(
                depth instanceof FlatJson.NumberToken,
                "maxDepth: требуется целое JSON-число"
        );

        String token = ((FlatJson.NumberToken) depth).text();

        require(
                token.matches("0|[1-9][0-9]*"),
                "maxDepth: требуется целое число от 0 до " + Integer.MAX_VALUE
        );

        return parseDepth(token);
    }

    private static int parseDepth(String token) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "maxDepth: значение превышает " + Integer.MAX_VALUE,
                    e
            );
        }
    }

    private static void validateRepository(
            String repository,
            boolean testMode,
            Path configDirectory) {
        if (testMode) {
            validateLocalFile(repository, configDirectory);
        } else {
            validateUrl(repository);
        }
    }

    private static void validateLocalFile(
            String repository, Path configDirectory) {
        Path local = configDirectory.resolve(Path.of(repository)).normalize();

        require(
                Files.isRegularFile(local),
                "repository: тестовый файл не найден: " + local
        );
        require(
                Files.isReadable(local),
                "repository: тестовый файл недоступен для чтения: " + local
        );
    }

    private static void validateUrl(String repository) {
        try {
            URI uri = new URI(repository);
            require(isValidUrl(uri), INVALID_URL);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(
                    "repository: некорректный URL",
                    e
            );
        }
    }

    private static boolean isValidUrl(URI uri) {
        boolean validScheme = "https".equalsIgnoreCase(uri.getScheme())
                || "http".equalsIgnoreCase(uri.getScheme());

        return validScheme
                && uri.getHost() != null
                && uri.getUserInfo() == null
                && uri.getFragment() == null
                && uri.getPort() >= UNDEFINED_PORT
                && uri.getPort() <= MAX_PORT;
    }

    private static String readString(
            Map<String, Object> values, String key) {
        Object value = values.get(key);
        require(value instanceof String, key + ": требуется строка");
        return (String) value;
    }

    private static boolean readBoolean(
            Map<String, Object> values, String key) {
        Object value = values.get(key);

        require(
                value instanceof Boolean,
                key + ": требуется true или false без кавычек"
        );

        return (Boolean) value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}