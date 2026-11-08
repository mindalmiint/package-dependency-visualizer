import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Запускает программу и обрабатывает ошибки конфигурации и загрузки. */
public final class Main {
    private static final int MAX_ARGUMENTS = 1;
    private static final int NO_ARGUMENTS = 0;
    private static final int DATA_ERROR = 1;
    private static final int ARGUMENT_ERROR = 2;
    private static final String DEFAULT_CONFIG = "config.json";

    /**
     * Читает настройки и выводит прямые зависимости пакета.
     *
     * @param args необязательный путь к конфигурации
     */
    public static void main(String[] args) {
        if (args.length > MAX_ARGUMENTS) {
            System.err.println(
                    "Использование: java -cp out Main [путь-к-config.json]"
            );
            System.exit(ARGUMENT_ERROR);
        }

        try {
            Path file = Path.of(
                    args.length == NO_ARGUMENTS
                            ? DEFAULT_CONFIG
                            : args[0]
            ).toAbsolutePath().normalize();

            String json = Files.readString(file, StandardCharsets.UTF_8);
            Config config = ConfigLoader.load(json, file.getParent());
            config.print();
            printDependencies(config, file.getParent());
        } catch (IOException exception) {
            System.err.println(
                    "Ошибка чтения конфигурации: " + exception.getMessage()
            );
            System.exit(DATA_ERROR);
        } catch (IllegalArgumentException | SecurityException exception) {
            System.err.println(
                    "Ошибка конфигурации: " + exception.getMessage()
            );
            System.exit(DATA_ERROR);
        }
    }

    private static void printDependencies(
            Config config, Path configDirectory) {
        try {
            NuGetClient.PackageData data = new NuGetClient().load(
                    config, configDirectory
            );

            data.print();
        } catch (IOException | IllegalArgumentException
                 | SecurityException exception) {
            System.err.println(
                    "Ошибка получения зависимостей: "
                            + exception.getMessage()
            );
            System.exit(DATA_ERROR);
        }
    }
}