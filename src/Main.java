import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Запускает программу и обрабатывает ошибки. */
public final class Main {
    private static final int MAX_ARGUMENTS = 1;
    private static final int NO_ARGUMENTS = 0;
    private static final int CONFIG_ERROR = 1;
    private static final int ARGUMENT_ERROR = 2;
    private static final String DEFAULT_CONFIG = "config.json";

    /**
     * Читает конфигурацию и выводит проверенные настройки.
     *
     * @param args необязательный путь к файлу конфигурации
     */
    public static void main(String[] args) {
        if (args.length > MAX_ARGUMENTS) {
            System.err.println("Использование: java -cp out Main [путь-к-config.json]");
            System.exit(ARGUMENT_ERROR);
        }

        try {
            Path file = Path.of(
                    args.length == NO_ARGUMENTS ? DEFAULT_CONFIG : args[0]
            ).toAbsolutePath().normalize();

            String json = Files.readString(file, StandardCharsets.UTF_8);
            Config config = ConfigLoader.load(json, file.getParent());
            config.print();
        } catch (IOException e) {
            System.err.println("Ошибка чтения конфигурации: " + e.getMessage());
            System.exit(CONFIG_ERROR);
        } catch (IllegalArgumentException | SecurityException e) {
            System.err.println("Ошибка конфигурации: " + e.getMessage());
            System.exit(CONFIG_ERROR);
        }
    }
}
