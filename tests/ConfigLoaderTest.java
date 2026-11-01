import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Проверяет чтение настроек и обработку неправильных параметров.
 */
public final class ConfigLoaderTest {
    private static final int EXPECTED_DEPTH = 3;
    private static Path directory;

    /**
     * Создаёт временный репозиторий и запускает проверки.
     *
     * @param args аргументы запуска, не используются
     * @throws IOException если не удалось создать или удалить тестовый файл
     */
    public static void main(String[] args) throws IOException {
        directory = Files.createTempDirectory("config-test-");
        Path repository = directory.resolve("test-repository.json");

        try {
            Files.writeString(repository, "{}");
            checkValidConfig();
            checkInvalidParameters();
            System.out.println("Все проверки пройдены.");
        } finally {
            Files.deleteIfExists(repository);
            Files.deleteIfExists(directory);
        }
    }

    private static void checkValidConfig() {
        Config config = ConfigLoader.load(configuration(null, null), directory);

        if (!"demo-app".equals(config.packageName())) {
            throw new AssertionError("Неправильно прочитано имя пакета");
        }
        if (config.maxDepth() != EXPECTED_DEPTH) {
            throw new AssertionError("Неправильно прочитана глубина");
        }
        System.out.println("OK: правильная конфигурация");
    }

    private static void checkInvalidParameters() {
        expectError("packageName", "\"\"", "packageName");
        expectError("repository", "\"missing.json\"", "repository");
        expectError("testMode", "\"true\"", "testMode");
        expectError("asciiTree", "1", "asciiTree");
        expectError("maxDepth", "-1", "maxDepth");
        expectError("filter", "123", "filter");
        expectError("filter", null, "Отсутствует обязательный параметр");
        expectError("extra", "true", "Неизвестный параметр");
    }

    private static void expectError(
            String key, String value, String expectedMessage) {
        try {
            ConfigLoader.load(configuration(key, value), directory);
        } catch (IllegalArgumentException e) {
            if (!e.getMessage().contains(expectedMessage)) {
                throw new AssertionError("Неожиданная ошибка: " + e.getMessage());
            }
            System.out.println("OK: обнаружена ошибка — " + key);
            return;
        }
        throw new AssertionError("Ошибка не обнаружена: " + key);
    }

    private static String configuration(String key, String value) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("packageName", "\"demo-app\"");
        fields.put("repository", "\"test-repository.json\"");
        fields.put("testMode", "true");
        fields.put("asciiTree", "true");
        fields.put("maxDepth", "3");
        fields.put("filter", "\"\"");

        if (key != null) {
            if (value == null) {
                fields.remove(key);
            } else {
                fields.put(key, value);
            }
        }
        return toJson(fields);
    }

    private static String toJson(Map<String, String> fields) {
        StringBuilder json = new StringBuilder("{");
        String separator = "";

        for (Map.Entry<String, String> field : fields.entrySet()) {
            json.append(separator);
            json.append('"').append(field.getKey()).append("\": ");
            json.append(field.getValue());
            separator = ",";
        }
        return json.append('}').toString();
    }
}