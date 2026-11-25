package eu.dariolucia.jsatdecode.inspector;

import eu.dariolucia.jsatdecode.api.source.SourcePropertyDescriptor;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Descriptor-driven editor. Blank optional fields leave provider defaults intact. EDT only. */
final class SourceConfigurationPanel extends JPanel {
    private final List<SourcePropertyDescriptor> properties;
    private final Map<String, String> original;
    private final Map<String, JComponent> editors = new LinkedHashMap<>();
    private final Map<String, JButton> browsers = new LinkedHashMap<>();

    SourceConfigurationPanel(List<SourcePropertyDescriptor> properties, Map<String, String> values) {
        super(new GridBagLayout());
        this.properties = List.copyOf(properties);
        original = Map.copyOf(values);
        int row = 0;
        for (SourcePropertyDescriptor property : properties) {
            String value = values.getOrDefault(property.name(), property.defaultValue());
            JComponent editor;
            if (property.type() == SourcePropertyDescriptor.Type.BOOLEAN || !property.allowedValues().isEmpty()) {
                JComboBox<String> choices = new JComboBox<>();
                choices.addItem("");
                List<String> allowed = property.type() == SourcePropertyDescriptor.Type.BOOLEAN
                        ? List.of("true", "false") : property.allowedValues();
                allowed.forEach(choices::addItem);
                if (value != null && !allowed.contains(value)) choices.addItem(value);
                choices.setSelectedItem(value == null ? "" : value);
                editor = choices;
            } else {
                editor = new JTextField(value == null ? "" : value, 12);
            }
            editor.setName("source." + property.name());
            editor.setMinimumSize(new Dimension(40, editor.getPreferredSize().height));
            String help = property.description() + " Key: source." + property.name()
                    + (property.unit().isEmpty() ? "" : ". Unit: " + property.unit())
                    + (property.defaultValue() == null ? "" : ". Default: " + property.defaultValue())
                    + (property.minimum() == null ? "" : ". Minimum: " + property.minimum())
                    + (property.maximum() == null ? "" : ". Maximum: " + property.maximum())
                    + (property.runtimeUpdatable() ? ". Editable while connected; use Apply source changes." : ". Requires reconnecting.");
            editor.setToolTipText(help);
            JLabel label = new JLabel(property.label() + (property.required() ? " *" : ""));
            label.setLabelFor(editor);
            label.setToolTipText(help);
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = row++;
            c.gridx = 0;
            c.anchor = GridBagConstraints.LINE_START;
            c.insets = new Insets(3, 1, 3, 7);
            add(label, c);
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            editors.put(property.name(), editor);
            if (property.type() == SourcePropertyDescriptor.Type.PATH) {
                JPanel path = new JPanel(new BorderLayout(3, 0));
                path.add(editor, BorderLayout.CENTER);
                JButton browse = new JButton("…");
                browse.setToolTipText("Choose " + property.label());
                JTextField text = (JTextField) editor;
                browse.addActionListener(event -> {
                    JFileChooser chooser = new JFileChooser();
                    // PATH can describe a file or a directory; providers validate its meaning.
                    chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
                    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
                        text.setText(chooser.getSelectedFile().getAbsolutePath());
                });
                path.add(browse, BorderLayout.EAST);
                browsers.put(property.name(), browse);
                add(path, c);
            } else add(editor, c);
        }
        if (properties.isEmpty()) add(new JLabel("This source does not advertise configuration properties."));
        // GridBagLayout uses minimum sizes in both axes when the sidebar is
        // narrower than preferred. Retain row heights while allowing width to shrink.
        setMinimumSize(new Dimension(0, getPreferredSize().height));
    }

    Map<String, String> values() {
        // Preserve unadvertised legacy/provider extension keys as well.
        Map<String, String> result = new LinkedHashMap<>(original);
        for (SourcePropertyDescriptor property : properties) {
            JComponent editor = editors.get(property.name());
            String value = editor instanceof JTextField field ? field.getText()
                    : (String) ((JComboBox<?>) editor).getSelectedItem();
            if (value == null || value.isBlank() || !original.containsKey(property.name()) && value.equals(property.defaultValue()))
                result.remove(property.name());
            else result.put(property.name(), value);
        }
        return Map.copyOf(result);
    }

    void validateValues() {
        Map<String, String> values = values();
        for (SourcePropertyDescriptor property : properties) {
            String value = values.get(property.name());
            if (value != null) property.parse(value);
        }
    }

    void onChange(Runnable listener) {
        for (JComponent editor : editors.values()) {
            if (editor instanceof JTextField text) text.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { listener.run(); }
                @Override public void removeUpdate(DocumentEvent e) { listener.run(); }
                @Override public void changedUpdate(DocumentEvent e) { listener.run(); }
            });
            else if (editor instanceof JComboBox<?> combo) combo.addActionListener(event -> listener.run());
        }
    }

    void updateEnabled(boolean active, boolean busy) {
        for (SourcePropertyDescriptor property : properties) {
            boolean enabled = !busy && (!active || property.runtimeUpdatable());
            editors.get(property.name()).setEnabled(enabled);
            JButton browser = browsers.get(property.name());
            if (browser != null) browser.setEnabled(enabled);
        }
    }
}
