/*
 * Copyright (C) 2013-2017 microG Project Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.microg.address;

import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Formatter {
    public static final String DEFAULT_PATH = "org/microg/address/conf";

    private static final Pattern VAR_PATTERN = Pattern.compile(".*\\$(\\w*).*");

    private static final String COMPONENT_ATTENTION = "attention";
    private static final String COMPONENT_COUNTRY = "country";
    private static final String COMPONENT_COUNTRY_CODE = "country_code";
    private static final String COMPONENT_POSTCODE = "postcode";
    private static final String COMPONENT_ROAD = "road";
    private static final String COMPONENT_STATE = "state";
    private static final String COMPONENT_STATE_CODE = "state_code";

    private static final String TEMPLATE_DEFAULT = "default";

    private final String path;
    private Map<String, Template> templates;
    private Map<String, String> componentAliases;
    private Map<String, List<String>> orderedComponents;
    private Map<String, Map<String, String>> stateCodes;

    public Formatter() throws IOException {
        this(DEFAULT_PATH);
    }

    public Formatter(String path) throws IOException {
        this.path = path;
        readConfiguration();
    }

    public String guessName(Map<String, String> components) {
        components = ensureValidMap(components);
        prepareRendering(components);

        return components.get(COMPONENT_ATTENTION);
    }

    public List<String> guessTypeCandidates(Map<String, String> components) {
        components = ensureValidMap(components);
        sanitizeComponents(components);
        return findUnknownComponents(components);
    }

    public String formatAddress(Map<String, String> components) {
        components = ensureValidMap(components);
        Template config = prepareRendering(components);

        String rendered = renderTemplate(components, chooseAddressTemplate(components, config));

        for (Template.Replacement replacement : config.postformatReplace()) {
            rendered = rendered.replaceAll(replacement.getFrom(), replacement.getTo());
        }

        return clean(rendered);
    }

    private Template prepareRendering(Map<String, String> components) {
        sanitizeComponents(components);
        Template config = selectTemplateFromCountryCode(components.get(COMPONENT_COUNTRY_CODE));

        applyReplacements(components, config.replace());
        addStateCode(components);
        configureAttention(components);
        return config;
    }

    private Template selectTemplateFromCountryCode(String cc) {
        return templates.containsKey(cc) ? templates.get(cc) : templates.get(TEMPLATE_DEFAULT);
    }

    private void sanitizeComponents(Map<String, String> components) {
        String cc = determineCountryCode(components);
        if (cc != null) components.put(COMPONENT_COUNTRY_CODE, cc);

        for (String alias : componentAliases.keySet()) {
            if (components.containsKey(alias) && !components.containsKey(componentAliases.get(alias))) {
                components.put(componentAliases.get(alias), components.get(alias));
            }
        }

        sanityCleaning(components);
        fixCountry(components);
    }

    private void configureAttention(Map<String, String> components) {
        List<String> unknown = findUnknownComponents(components);
        if (!unknown.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String s : unknown) {
                if (sb.length() != 0) sb.append(", ");
                sb.append(components.get(s));
            }
            components.put(COMPONENT_ATTENTION, sb.toString());
        }
    }

    private String chooseAddressTemplate(Map<String, String> components, Template config) {
        String template = config.addressTemplate();

        if (!minimalComponents(components)) {
            if (config.fallbackTemplate() != null) {
                template = config.fallbackTemplate();
            } else if (templates.get(TEMPLATE_DEFAULT).fallbackTemplate() != null) {
                template = templates.get(TEMPLATE_DEFAULT).fallbackTemplate();
            }
        }

        return template.replace("\r\n", "\n");
    }

    private Map<String, String> ensureValidMap(Map<String, String> components) {
        components = new HashMap<String, String>(components);
        for (String key : components.keySet()) {
            components.put(key, String.valueOf(components.get(key)));
        }
        return components;
    }

    private String clean(String in) {
        in = in.replaceAll("\\s*\\n", "\n").replaceAll("  +", " ");

        String[] lines = in.split("\n");
        List<String> prevlines = new ArrayList<String>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            while (line.startsWith(",") || line.startsWith("-")) line = line.substring(1).trim();
            while (line.endsWith(",") || line.endsWith("-"))
                line = line.substring(0, line.length() - 1).trim();
            if (!prevlines.contains(line) && !line.isEmpty()) {
                String[] split1 = line.split(",");
                StringBuilder sb2 = new StringBuilder();
                String prev = null;
                for (String s : split1) {
                    if (s.trim().equals(prev)) continue;
                    if (prev != null) sb2.append(",");
                    prev = s.trim();
                    sb2.append(s);
                }
                prevlines.add(sb2.toString());
                sb.append(sb2.toString()).append("\n");
            }
        }

        return sb.toString().trim();
    }

    private String renderTemplate(Map<String, String> components, String template) {
        for (String s : components.keySet()) {
            template = template.replace("{{{" + s + "}}}", components.get(s));
        }

        template = template.replaceAll("\\{\\{\\{[^\\}]*\\}\\}\\}", "");

        String[] split = template.split("\\{\\{#first\\}\\}");
        StringBuilder sb = new StringBuilder(split[0]);
        for (int i = 0; i < split.length; i++) {
            if (i == 0) continue;
            String[] s = split[i].split("\\{\\{/first\\}\\}");
            String[] c = s[0].split("\\|\\|");
            String h = null;
            for (String s1 : c) {
                if (!s1.trim().isEmpty()) {
                    h = s1.trim();
                    break;
                }
            }
            if (h != null)
                sb.append(h);
            sb.append(s[1]);
        }

        template = sb.toString();
        return template;
    }

    private void sanityCleaning(Map<String, String> components) {
        if (components.containsKey(COMPONENT_POSTCODE)) {
            if (components.get(COMPONENT_POSTCODE).length() > 20 || Pattern.compile("\\d+;\\d+").matcher(components.get(COMPONENT_POSTCODE)).matches())
                components.remove(COMPONENT_POSTCODE);
        }

        for (String key : new HashSet<String>(components.keySet())) {
            if (components.get(key).contains("http://") || components.get(key).contains("https://"))
                components.remove(key);
        }
    }

    private List<String> findUnknownComponents(Map<String, String> components) {
        List<String> unknown = new ArrayList<String>();
        for (String s : components.keySet()) {
            if (!orderedComponents.containsKey(s) && !componentAliases.containsKey(s))
                unknown.add(s);
        }
        return unknown;
    }

    private boolean minimalComponents(Map<String, String> components) {
        String[] requiredComponents = new String[]{COMPONENT_ROAD, COMPONENT_POSTCODE};
        int missing = 0;
        int minimalThreshold = 2;

        for (String c : requiredComponents) {
            if (!components.containsKey(c)) missing++;
            if (missing == minimalThreshold) return false;
        }
        return true;
    }

    private void addStateCode(Map<String, String> components) {
        if (components.containsKey(COMPONENT_STATE_CODE)) return;
        if (!components.containsKey(COMPONENT_STATE)) return;
        if (!components.containsKey(COMPONENT_COUNTRY_CODE)) return;

        components.put(COMPONENT_COUNTRY_CODE, components.get(COMPONENT_COUNTRY_CODE).toUpperCase());

        Map<String, String> mapping = stateCodes.get(components.get(COMPONENT_COUNTRY_CODE));
        if (mapping != null) {
            for (Object s : mapping.keySet()) {
                if (components.get(COMPONENT_STATE).toUpperCase().equals(mapping.get(s).toUpperCase())) {
                    components.put(COMPONENT_STATE_CODE, String.valueOf(s));
                }
            }
        }
    }

    private void applyReplacements(Map<String, String> components, List<Template.Replacement> rules) {
        for (String component : components.keySet()) {
            for (Template.Replacement fromto : rules) {
                if (fromto.getFrom().matches("^" + component + "=.*")) {
                    if (fromto.getFrom().substring(component.length() + 1).equals(components.get(component))) {
                        components.put(component, fromto.getTo());
                    }
                } else {
                    components.put(component, components.get(component).replaceAll(fromto.getFrom(), fromto.getTo()));
                }
            }
        }
    }

    private void fixCountry(Map<String, String> components) {
        if (components.containsKey(COMPONENT_COUNTRY) && components.containsKey(COMPONENT_STATE)) {
            try {
                Integer.parseInt(components.get(COMPONENT_COUNTRY));
                components.put(COMPONENT_COUNTRY, components.get(COMPONENT_STATE));
                components.remove(COMPONENT_STATE);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private String determineCountryCode(Map<String, String> components) {
        if (!components.containsKey(COMPONENT_COUNTRY_CODE)) return null;
        String cc = components.get(COMPONENT_COUNTRY_CODE).toUpperCase();
        if (cc.length() != 2) return null;
        if (cc.equals("UK")) return "GB";
        if (templates.containsKey(cc) && templates.get(cc).useCountry() != null) {
            String oldcc = cc;
            cc = templates.get(cc).useCountry();
            if (templates.get(oldcc).changeCountry() != null) {
                String newCountry = templates.get(oldcc).changeCountry();
                Matcher matcher = VAR_PATTERN.matcher(newCountry);
                if (matcher.matches()) {
                    String component = matcher.group(1);
                    newCountry = newCountry.replace("$" + component, components.get(component));
                }
                components.put(COMPONENT_COUNTRY, newCountry);
            }
            if (templates.get(oldcc).addComponent() != null) {
                String[] split = templates.get(oldcc).addComponent().split("=");
                components.put(split[0], split[1]);
            }
        }

        if (cc.equals("NL")) {
            if (components.containsKey(COMPONENT_STATE)) {
                if (components.get(COMPONENT_STATE).equals("Curaçao")) {
                    cc = "CW";
                    components.put(COMPONENT_COUNTRY, "Curaçao");
                }
                if (components.get(COMPONENT_STATE).equalsIgnoreCase("sint maarten")) {
                    cc = "SX";
                    components.put(COMPONENT_COUNTRY, "Sint Maarten");
                }
                if (components.get(COMPONENT_STATE).equalsIgnoreCase("Aruba")) {
                    cc = "AW";
                    components.put(COMPONENT_COUNTRY, "Aruba");
                }
            }
        }
        return cc;
    }

    private void readConfiguration() throws IOException {
        List<String> filenames = findFilesInPath(path + "/countries", "*.yaml");
        Collections.sort(filenames);
        templates = new HashMap<String, Template>();
        for (String filename : filenames) {
            Object o = loadFile(filename).iterator().next();
            if (!(o instanceof Map)) continue;
            Map map = (Map) o;
            for (Object k : map.keySet()) {
                if (k instanceof String) {
                    templates.put((String) k, Template.parse(map.get(k)));
                }
            }
        }

        componentAliases = new HashMap<String, String>();
        orderedComponents = new HashMap<String, List<String>>();
        for (Object o : loadFile(path + "/components.yaml")) {
            if (!(o instanceof Map)) continue;
            Map m = (Map) o;
            String name = getString(m, "name");
            List<String> aliases = getStringList(m, "aliases");
            for (String alias : aliases) {
                componentAliases.put(alias, name);
            }
            orderedComponents.put(name, aliases);
        }

        //stateCodes = new HashMap<String, Map<String, String>>();
        stateCodes = (Map<String, Map<String, String>>) loadFile(path + "/state_codes.yaml").iterator().next();

    }

    static Iterable<Object> loadFile(String filename) {
        return new Yaml().loadAll(open(filename));
    }

    static InputStream open(String filename) {
        return Formatter.class.getClassLoader().getResourceAsStream(filename);
    }

    static List<String> findFilesInPath(String path, String pattern) throws IOException {
        InputStream open = open(path + "/index.list");
        if (open == null) throw new FileNotFoundException("No file: " + path + "/index.list");
        String[] allFiles = readStreamAsString(open).split("\n");
        Pattern regex = Pattern.compile(pattern.replace("*", ".*"));
        List<String> res = new ArrayList<String>();
        for (String filename : allFiles) {
            if (regex.matcher(filename).matches()) res.add(path + "/" + filename);
        }
        return res;
    }

    static String readStreamAsString(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] arr = new byte[2048];
        while (is.available() > 0) {
            int c = is.read(arr);
            if (c == -1) break;
            bos.write(arr, 0, c);
        }
        return new String(bos.toByteArray());
    }

    static List<String> getStringList(Map m, String key) {
        if (!(m.get(key) instanceof List)) return Collections.emptyList();
        List l1 = (ArrayList) m.get(key);
        if (l1.get(0) instanceof String) return l1;
        return Collections.emptyList();
    }

    static String getString(Map m, String key) {
        if (m.containsKey(key)) return String.valueOf(m.get(key));
        return null;
    }
}
