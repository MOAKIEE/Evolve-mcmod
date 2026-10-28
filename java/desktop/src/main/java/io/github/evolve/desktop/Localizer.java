package io.github.evolve.desktop;

import io.github.evolve.core.TextRef;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.regex.Pattern;

/** Desktop translation adapter. Minecraft can use Component.translatable with the same TextRef. */
public final class Localizer {
    private static final Pattern TOKEN = Pattern.compile("%([0-9]+)");
    private final Properties strings;

    public Localizer(String locale) {
        strings = load("en_us");
        if (!locale.equals("en_us")) strings.putAll(load(locale));
    }

    public String text(String key, Object... arguments) { return text(TextRef.of(key, arguments)); }

    public String text(TextRef reference) {
        String template = strings.getProperty(reference.key(), reference.key());
        var matcher = TOKEN.matcher(template);
        var output = new StringBuilder();
        int position = 0;
        while (matcher.find()) {
            output.append(template, position, matcher.start());
            int index = Integer.parseInt(matcher.group(1));
            output.append(index < reference.arguments().size() ? reference.arguments().get(index) : matcher.group());
            position = matcher.end();
        }
        return output.append(template, position, template.length()).toString();
    }

    private static Properties load(String locale) {
        if (!java.util.Set.of("en_us", "zh_cn").contains(locale)) throw new IllegalArgumentException("Unknown locale");
        try (var stream = Localizer.class.getResourceAsStream("/evolve/lang/" + locale + ".properties")) {
            if (stream == null) throw new IllegalStateException("Missing locale: " + locale);
            var properties = new Properties();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return properties;
        } catch (IOException e) { throw new IllegalStateException("Cannot load locale", e); }
    }
}
