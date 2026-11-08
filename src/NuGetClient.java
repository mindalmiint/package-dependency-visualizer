import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Получает прямые зависимости NuGet средствами стандартной библиотеки Java. */
public final class NuGetClient {
    private static final String CONTENT_TYPE = "PackageBaseAddress/3.0.0";
    private static final String ANY_FRAMEWORK = "any";
    private static final int HTTP_OK = 200;
    private static final int VERSION_PARTS = 4;
    private static final int EQUAL = 0;
    private static final int SINGLE_ELEMENT = 1;
    private static final int UNDEFINED_PORT = -1;
    private static final int MAX_PORT = 65535;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Хранит имя зависимости, диапазон версий и целевую платформу. */
    public record Dependency(
            String id,
            String versionRange,
            String targetFramework) {
    }

    /** Хранит выбранный пакет и только его прямые зависимости. */
    public record PackageData(
            String id,
            String version,
            List<Dependency> dependencies) {

        /** Сохраняет неизменяемую копию списка зависимостей. */
        public PackageData {
            dependencies = List.copyOf(dependencies);
        }

        /** Выводит пакет и все найденные прямые зависимости. */
        public void print() {
            System.out.println("Пакет: " + id + " | version=" + version);
            System.out.println("Прямые зависимости:");

            if (dependencies.isEmpty()) {
                System.out.println("Прямых зависимостей нет.");
                return;
            }

            for (Dependency dependency : dependencies) {
                System.out.println(
                        dependency.id()
                                + " | version=" + dependency.versionRange()
                                + " | framework=" + dependency.targetFramework()
                );
            }
        }
    }

    /**
     * Выбирает локальный или сетевой источник согласно конфигурации.
     *
     * @param config проверенная конфигурация
     * @param configDirectory каталог конфигурационного файла
     * @return пакет с прямыми зависимостями
     * @throws IOException при ошибке чтения или HTTP-запроса
     */
    public PackageData load(Config config, Path configDirectory)
            throws IOException {
        if (config.testMode()) {
            return readLocal(
                    configDirectory.resolve(config.repository()).normalize(),
                    config.packageName()
            );
        }

        return readRemote(config.repository(), config.packageName());
    }

    private PackageData readRemote(String repository, String packageName)
            throws IOException {
        String id = normalizeId(packageName);
        URI base = findBaseAddress(URI.create(repository));

        Map<String, Object> response = getJson(
                base.resolve(id + "/index.json")
        );

        String version = latestStable(asArray(response.get("versions")));

        URI manifest = base.resolve(
                id + "/" + version + "/" + id + ".nuspec"
        );

        return readManifest(get(manifest), packageName, version);
    }

    private PackageData readLocal(Path file, String packageName)
            throws IOException {
        Map<String, Object> packages = normalizePackages(
                FlatJson.parse(
                        Files.readString(file, StandardCharsets.UTF_8)
                )
        );

        String id = normalizeId(packageName);

        if (!packages.containsKey(id)) {
            throw new IllegalArgumentException(
                    "Пакет не найден в тестовом репозитории: " + packageName
            );
        }

        List<Dependency> result = new ArrayList<>();

        for (Object value : asArray(packages.get(id))) {
            String dependencyId = asString(value);
            normalizeId(dependencyId);
            result.add(new Dependency(dependencyId, "*", ANY_FRAMEWORK));
        }

        return new PackageData(
                packageName, "test", result.stream().distinct().toList()
        );
    }

    private static Map<String, Object> normalizePackages(
            Map<String, Object> packages) {
        Map<String, Object> result = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : packages.entrySet()) {
            String id = normalizeId(entry.getKey());

            if (result.containsKey(id)) {
                throw new IllegalArgumentException(
                        "Повторяющийся идентификатор пакета: " + id
                );
            }

            result.put(id, entry.getValue());
        }

        return result;
    }

    private static String normalizeId(String id) {
        if (!id.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*")) {
            throw new IllegalArgumentException(
                    "Недопустимый идентификатор NuGet: " + id
            );
        }

        return id.toLowerCase(Locale.ROOT);
    }

    private URI findBaseAddress(URI repository) throws IOException {
        Map<String, Object> index = getJson(repository);

        if (!asString(index.get("version")).startsWith("3.")) {
            throw new IllegalArgumentException("Требуется NuGet V3");
        }

        for (Object value : asArray(index.get("resources"))) {
            Map<?, ?> resource = asObject(value);

            if (CONTENT_TYPE.equals(resource.get("@type"))) {
                return contentAddress(asString(resource.get("@id")));
            }
        }

        throw new IllegalArgumentException(
                "В репозитории отсутствует " + CONTENT_TYPE
        );
    }

    private static URI contentAddress(String address) {
        URI uri = URI.create(address);
        validateUri(uri);

        if (uri.getQuery() != null) {
            throw new IllegalArgumentException(
                    "Адрес PackageBaseAddress не должен содержать query"
            );
        }

        return URI.create(address.endsWith("/") ? address : address + "/");
    }

    private Map<String, Object> getJson(URI uri) throws IOException {
        return FlatJson.parse(
                new String(get(uri), StandardCharsets.UTF_8)
        );
    }

    private byte[] get(URI uri) throws IOException {
        validateUri(uri);

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        try {
            HttpResponse<byte[]> response = http.send(
                    request,
                    HttpResponse.BodyHandlers.ofByteArray()
            );

            if (response.statusCode() != HTTP_OK) {
                throw new IOException(
                        "Ошибка репозитория HTTP "
                                + response.statusCode() + ": " + uri
                );
            }

            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP-запрос прерван", exception);
        }
    }

    private static void validateUri(URI uri) {
        boolean httpScheme = "http".equalsIgnoreCase(uri.getScheme());
        boolean httpsScheme = "https".equalsIgnoreCase(uri.getScheme());
        boolean validPort = uri.getPort() >= UNDEFINED_PORT
                && uri.getPort() <= MAX_PORT;

        if (!(httpScheme || httpsScheme) || !validPort
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "Требуется HTTP/HTTPS URL без учётных данных и фрагмента"
            );
        }
    }

    /** Выбирает максимальную стабильную версию из ответа NuGet. */
    static String latestStable(List<?> versions) {
        String latest = null;

        for (Object value : versions) {
            String version = asString(value);

            if (version.contains("-")) continue;

            versionParts(version);

            if (latest == null || compareVersions(version, latest) > EQUAL) {
                latest = version;
            }
        }

        if (latest == null) {
            throw new IllegalArgumentException(
                    "У пакета нет стабильных версий"
            );
        }

        return latest;
    }

    private static int compareVersions(String left, String right) {
        BigInteger[] leftParts = versionParts(left);
        BigInteger[] rightParts = versionParts(right);

        for (int index = 0; index < VERSION_PARTS; index++) {
            int order = leftParts[index].compareTo(rightParts[index]);

            if (order != EQUAL) return order;
        }

        return EQUAL;
    }

    private static BigInteger[] versionParts(String version) {
        if (!version.matches("[0-9]+(?:\\.[0-9]+){0,3}")) {
            throw new IllegalArgumentException(
                    "Некорректная стабильная версия NuGet: " + version
            );
        }

        String[] components = version.split("\\.");
        BigInteger[] result = new BigInteger[VERSION_PARTS];

        for (int index = 0; index < VERSION_PARTS; index++) {
            result[index] = index < components.length
                    ? new BigInteger(components[index])
                    : BigInteger.ZERO;
        }

        return result;
    }

    /**
     * Читает прямые зависимости из XML-манифеста выбранного пакета.
     *
     * @param xml байты манифеста .nuspec
     * @param expectedId запрошенный идентификатор
     * @param expectedVersion выбранная версия
     * @return пакет с зависимостями всех групп платформ
     */
    static PackageData readManifest(
            byte[] xml, String expectedId, String expectedVersion) {
        Element root = parseXml(xml);

        if (!"package".equals(root.getLocalName())) {
            throw new IllegalArgumentException(
                    "nuspec: требуется корневой элемент package"
            );
        }

        Element metadata = requiredChild(root, "metadata");
        String id = elementText(metadata, "id");
        String version = elementText(metadata, "version");
        checkIdentity(id, version, expectedId, expectedVersion);

        Element dependencies = optionalChild(metadata, "dependencies");
        List<Dependency> result = dependencies == null
                ? List.of()
                : readDependencies(dependencies);

        return new PackageData(
                id, version, result.stream().distinct().toList()
        );
    }

    private static Element parseXml(byte[] xml) {
        try {
            DocumentBuilder builder = xmlFactory().newDocumentBuilder();

            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void fatalError(SAXParseException exception)
                        throws SAXException {
                    throw exception;
                }
            });

            return builder.parse(
                    new ByteArrayInputStream(xml)
            ).getDocumentElement();
        } catch (ParserConfigurationException
                 | SAXException | IOException exception) {
            throw new IllegalArgumentException(
                    "nuspec: некорректный или небезопасный XML",
                    exception
            );
        }
    }

    private static DocumentBuilderFactory xmlFactory()
            throws ParserConfigurationException {
        DocumentBuilderFactory factory =
                DocumentBuilderFactory.newInstance();

        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl",
                true
        );
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        return factory;
    }

    private static void checkIdentity(
            String id, String version,
            String expectedId, String expectedVersion) {
        if (!normalizeId(id).equals(normalizeId(expectedId))) {
            throw new IllegalArgumentException(
                    "nuspec: идентификатор пакета не совпадает"
            );
        }

        String coreVersion = version.split("\\+", 2)[0];

        if (compareVersions(coreVersion, expectedVersion) != EQUAL) {
            throw new IllegalArgumentException(
                    "nuspec: версия пакета не совпадает"
            );
        }
    }

    private static List<Dependency> readDependencies(Element parent) {
        List<Dependency> result = new ArrayList<>();
        List<Element> flat = children(parent, "dependency");
        List<Element> groups = children(parent, "group");

        if (!flat.isEmpty() && !groups.isEmpty()) {
            throw new IllegalArgumentException(
                    "nuspec: нельзя смешивать dependency и group"
            );
        }

        appendDependencies(flat, ANY_FRAMEWORK, result);

        for (Element group : groups) {
            String framework = group.getAttribute(
                    "targetFramework"
            ).strip();

            appendDependencies(
                    children(group, "dependency"),
                    framework.isEmpty() ? ANY_FRAMEWORK : framework,
                    result
            );
        }

        return result;
    }

    private static void appendDependencies(
            List<Element> elements,
            String framework,
            List<Dependency> result) {
        for (Element element : elements) {
            String id = asString(element.getAttribute("id"));
            String version = asString(element.getAttribute("version"));
            normalizeId(id);
            result.add(new Dependency(id, version, framework));
        }
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();

        for (Node node = parent.getFirstChild();
             node != null; node = node.getNextSibling()) {
            if (node instanceof Element element
                    && name.equals(element.getLocalName())
                    && Objects.equals(
                    parent.getNamespaceURI(),
                    element.getNamespaceURI())) {
                result.add(element);
            }
        }

        return result;
    }

    private static Element optionalChild(Element parent, String name) {
        List<Element> elements = children(parent, name);

        if (elements.size() > SINGLE_ELEMENT) {
            throw new IllegalArgumentException(
                    "nuspec: повторяющийся элемент " + name
            );
        }

        return elements.isEmpty() ? null : elements.get(0);
    }

    private static Element requiredChild(Element parent, String name) {
        Element element = optionalChild(parent, name);

        if (element == null) {
            throw new IllegalArgumentException(
                    "nuspec: отсутствует элемент " + name
            );
        }

        return element;
    }

    private static String elementText(Element parent, String name) {
        return asString(
                requiredChild(parent, name).getTextContent().strip()
        );
    }

    private static Map<?, ?> asObject(Object value) {
        if (value instanceof Map<?, ?> result) return result;

        throw new IllegalArgumentException("Ожидается JSON-объект");
    }

    private static List<?> asArray(Object value) {
        if (value instanceof List<?> result) return result;

        throw new IllegalArgumentException("Ожидается JSON-массив");
    }

    private static String asString(Object value) {
        if (value instanceof String result && !result.isBlank()) {
            return result;
        }

        throw new IllegalArgumentException("Ожидается непустая строка");
    }
}