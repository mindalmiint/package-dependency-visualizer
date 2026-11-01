/** Хранит проверенные настройки программы. */
public record Config(
        String packageName,
        String repository,
        boolean testMode,
        boolean asciiTree,
        int maxDepth,
        String filter) {

    /** Выводит настройки в формате ключ=значение. */
    public void print() {
        System.out.println("packageName=" + escaped(packageName));
        System.out.println("repository=" + escaped(repository));
        System.out.println("testMode=" + testMode);
        System.out.println("asciiTree=" + asciiTree);
        System.out.println("maxDepth=" + maxDepth);
        System.out.println("filter=" + escaped(filter));
    }

    /** Экранирует обратный слеш и управляющие символы для вывода. */
    private static String escaped(String value) {
        StringBuilder result = new StringBuilder();

        for (char character : value.toCharArray()) {
            if (character == '\\') {
                result.append("\\\\");
            } else if (Character.isISOControl(character)) {
                result.append(String.format("\\u%04x", (int) character));
            } else {
                result.append(character);
            }
        }

        return result.toString();
    }
}
