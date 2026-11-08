import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Проверяет получение прямых зависимостей средствами стандартного JDK. */
public final class DependencyTest {
    private static final int HTTP_OK = 200;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int EXPECTED_REQUESTS = 3;

    private static final String XML =
            "<package><metadata>"
                    + "<id>Demo.App</id><version>2.10.0</version>"
                    + "<dependencies><group targetFramework=\"net8.0\">"
                    + "<dependency id=\"Logger\" version=\"[1.0,2.0)\"/>"
                    + "</group></dependencies></metadata></package>";

    /** Действие, которое может завершиться исключением. */
    @FunctionalInterface
    private interface Action {
        /** Выполняет проверяемое действие. */
        void run() throws Exception;
    }

    /**
     * Запускает проверки второго этапа без подключения к интернету.
     *
     * @param args аргументы запуска, не используются
     * @throws Exception при ошибке выполнения тестов
     */
    public static void main(String[] args) throws Exception {
        checkJson();
        checkVersions();
        checkManifest();
        checkManifestErrors();
        checkLocal();
        checkHttp();

        System.out.println(
                "DependencyTest: все проверки пройдены."
        );
    }

    /** Проверяет вложенные объекты, массивы и ошибки JSON. */
    private static void checkJson() {
        Object value = FlatJson.parse(
                "{\"items\":[{\"name\":\"Logger\"},true,null]}"
        ).get("items");

        if (!(value instanceof List<?> items)) {
            throw new AssertionError("Массив JSON не распознан");
        }

        equal(3, items.size());
        equal(Map.of("name", "Logger"), items.get(0));
        equal(true, items.get(1));
        equal(null, items.get(2));

        fails(
                IllegalArgumentException.class,
                () -> FlatJson.parse("{\"items\":[1,]}")
        );

        fails(
                IllegalArgumentException.class,
                () -> FlatJson.parse("{\"a\":1,\"a\":2}")
        );
    }

    /** Проверяет числовое сравнение версий и пропуск предварительных версий. */
    private static void checkVersions() {
        equal(
                "2.10.0",
                NuGetClient.latestStable(
                        List.of("2.9.0", "9.0.0-beta", "2.10.0", "1.0")
                )
        );

        equal(
                "1.0.0.10",
                NuGetClient.latestStable(
                        List.of("1.0.0.2", "1.0.0.10")
                )
        );

        fails(
                IllegalArgumentException.class,
                () -> NuGetClient.latestStable(List.of("1.0.0-beta"))
        );

        fails(
                IllegalArgumentException.class,
                () -> NuGetClient.latestStable(List.of())
        );
    }

    /** Проверяет зависимости с группами платформ, без групп и пустой список. */
    private static void checkManifest() {
        NuGetClient.PackageData data = readXml(XML);

        equal(
                List.of(new NuGetClient.Dependency(
                        "Logger", "[1.0,2.0)", "net8.0"
                )),
                data.dependencies()
        );

        String empty = "<package><metadata><id>Demo.App</id>"
                + "<version>2.10.0</version></metadata></package>";

        equal(List.of(), readXml(empty).dependencies());

        String flat = empty.replace(
                "</metadata>",
                "<dependencies><dependency id=\"Core\" version=\"1.0\"/>"
                        + "</dependencies></metadata>"
        );

        equal(
                List.of(new NuGetClient.Dependency("Core", "1.0", "any")),
                readXml(flat).dependencies()
        );
    }

    /** Проверяет несовпадение метаданных, ошибочный XML и запрет DOCTYPE. */
    private static void checkManifestErrors() {
        fails(
                IllegalArgumentException.class,
                () -> readXml(XML.replace("Demo.App", "Other"))
        );

        fails(
                IllegalArgumentException.class,
                () -> readXml(XML.replace("2.10.0", "1.0.0"))
        );

        fails(
                IllegalArgumentException.class,
                () -> readXml("<package>")
        );

        fails(
                IllegalArgumentException.class,
                () -> readXml("<!DOCTYPE package>" + XML)
        );
    }

    /** Разбирает манифест с ожидаемым идентификатором и версией. */
    private static NuGetClient.PackageData readXml(String xml) {
        return NuGetClient.readManifest(
                xml.getBytes(StandardCharsets.UTF_8),
                "demo.app",
                "2.10.0"
        );
    }

    /** Создаёт временный репозиторий и удаляет его после проверок. */
    private static void checkLocal() throws Exception {
        Path file = Files.createTempFile("repository-test-", ".json");

        try {
            checkLocalData(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** Проверяет прямые зависимости и ошибки локального репозитория. */
    private static void checkLocalData(Path file) throws Exception {
        Files.writeString(
                file,
                "{\"Demo.App\":[\"Logger\"],\"Logger\":[\"Transitive\"]}"
        );

        NuGetClient client = new NuGetClient();
        Config config = configuration(file.toString(), true);

        equal(
                List.of(new NuGetClient.Dependency("Logger", "*", "any")),
                client.load(config, Path.of(".")).dependencies()
        );

        Config missing = new Config(
                "Missing", file.toString(), true, false, 0, ""
        );

        fails(
                IllegalArgumentException.class,
                () -> client.load(missing, Path.of("."))
        );

        Files.writeString(file, "{\"Demo.App\":[]}");

        equal(
                List.of(),
                client.load(config, Path.of(".")).dependencies()
        );

        Files.writeString(file, "{\"Demo.App\":null}");

        fails(
                IllegalArgumentException.class,
                () -> client.load(config, Path.of("."))
        );
    }

    /** Проверяет полный HTTP-сценарий NuGet V3 и отсутствие пакета. */
    private static void checkHttp() throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0
        );

        AtomicInteger requests = new AtomicInteger();
        String base = "http://127.0.0.1:"
                + server.getAddress().getPort();

        registerRoutes(server, base, requests);
        server.start();

        try {
            NuGetClient client = new NuGetClient();
            Config config = configuration(base + "/index.json", false);
            NuGetClient.PackageData data = client.load(config, Path.of("."));

            equal("2.10.0", data.version());
            equal(
                    List.of(new NuGetClient.Dependency(
                            "Logger", "[1.0,2.0)", "net8.0"
                    )),
                    data.dependencies()
            );
            equal(EXPECTED_REQUESTS, requests.get());

            Config missing = new Config(
                    "Missing", base + "/index.json", false, false, 0, ""
            );

            fails(
                    IOException.class,
                    () -> client.load(missing, Path.of("."))
            );
        } finally {
            server.stop(0);
        }
    }

    /** Создаёт конфигурацию для проверки выбранного источника данных. */
    private static Config configuration(String repository, boolean testMode) {
        return new Config(
                "Demo.App", repository, testMode, false, 0, "no-match"
        );
    }

    /** Настраивает ответы локального сервера и подсчёт запросов. */
    private static void registerRoutes(
            HttpServer server, String base, AtomicInteger requests) {
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            String response = response(path, base);

            int status = response == null ? HTTP_NOT_FOUND : HTTP_OK;
            byte[] bytes = (response == null ? "not found" : response)
                    .getBytes(StandardCharsets.UTF_8);

            try (exchange) {
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
    }

    /** Возвращает тестовые ответы service index, списка версий и манифеста. */
    private static String response(String path, String base) {
        return switch (path) {
            case "/index.json" ->
                    "{\"version\":\"3.0.0\",\"resources\":["
                            + "{\"@type\":\"PackageBaseAddress/3.0.0\","
                            + "\"@id\":\"" + base + "/content/\"}]}";
            case "/content/demo.app/index.json" ->
                    "{\"versions\":[\"2.9.0\",\"9.0.0-beta\",\"2.10.0\"]}";
            case "/content/demo.app/2.10.0/demo.app.nuspec" -> XML;
            default -> null;
        };
    }

    /** Сравнивает ожидаемое и фактическое значения. */
    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(
                    "Ожидалось: " + expected + "; получено: " + actual
            );
        }
    }

    /** Проверяет, что действие завершается исключением ожидаемого типа. */
    private static void fails(
            Class<? extends Exception> expected, Action action) {
        try {
            action.run();
        } catch (Exception exception) {
            if (!expected.isInstance(exception)) {
                throw new AssertionError(
                        "Неожиданный тип исключения", exception
                );
            }

            return;
        }

        throw new AssertionError("Ожидалось исключение");
    }
}