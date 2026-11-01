package ide.ui;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/** The dialogs people see: a form per capability, a quick pick that filters as they type, a line, a message. */
final class SwingDialogs implements Dialogs {

    private final JFrame owner;

    SwingDialogs(JFrame owner) {
        this.owner = owner;
    }

    @Override
    public Optional<Map<String, Object>> fill(String title, Form form, Function<Map<String, Object>, String> preview) {
        var dialog = new JDialog(owner, title, true);
        var fields = new JPanel(new GridBagLayout());
        var readers = new LinkedHashMap<String, Supplier<Object>>();
        var row = 0;
        for (var field : form.fields()) {
            var param = field.param();
            JComponent input;
            switch (param.type()) {
                case "bool" -> {
                    var box = new JCheckBox();
                    box.setSelected(Boolean.TRUE.equals(field.value()));
                    readers.put(param.name(), box::isSelected);
                    input = box;
                }
                case "elements" -> {
                    var list = new JList<>(field.options().toArray(String[]::new));
                    list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
                    list.setVisibleRowCount(6);
                    readers.put(param.name(), list::getSelectedValuesList);
                    input = new JScrollPane(list);
                }
                default -> {
                    if (field.options().isEmpty()) {
                        var text = new JTextField(field.value() == null ? "" : String.valueOf(field.value()), 32);
                        readers.put(param.name(), text::getText);
                        input = text;
                    } else {
                        var combo = new JComboBox<>(field.options().toArray(String[]::new));
                        combo.setEditable(param.type().equals("element"));
                        combo.setSelectedItem(field.value());
                        readers.put(param.name(), combo::getSelectedItem);
                        input = combo;
                    }
                }
            }
            var label = new JLabel(param.name() + (param.required() ? "" : " (optional)"));
            label.setToolTipText(param.description());
            input.setToolTipText(param.description());
            fields.add(label, at(0, row));
            fields.add(input, at(1, row++));
        }
        var diff = new JTextArea(14, 90);
        diff.setEditable(false);
        diff.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        Map<String, Object>[] result = new Map[1];
        Supplier<Map<String, Object>> values = () -> {
            var read = new LinkedHashMap<String, Object>();
            readers.forEach((name, reader) -> read.put(name, reader.get()));
            return read;
        };
        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var show = new JButton("Preview");
        show.addActionListener(event -> diff.setText(preview.apply(values.get())));
        var ok = new JButton("OK");
        ok.addActionListener(event -> {
            result[0] = values.get();
            dialog.dispose();
        });
        var cancel = new JButton("Cancel");
        cancel.addActionListener(event -> dialog.dispose());
        buttons.add(show);
        buttons.add(cancel);
        buttons.add(ok);
        var content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(fields, BorderLayout.PAGE_START);
        content.add(new JScrollPane(diff), BorderLayout.CENTER);
        content.add(buttons, BorderLayout.PAGE_END);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(ok);
        closeOnEscape(dialog);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return Optional.ofNullable(result[0]);
    }

    @Override
    public Optional<String> choose(String title, List<String> options) {
        var dialog = new JDialog(owner, title, true);
        var query = new JTextField(48);
        var model = new DefaultListModel<String>();
        model.addAll(options);
        var list = new JList<>(model);
        list.setVisibleRowCount(16);
        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        list.setSelectedIndex(0);
        String[] chosen = new String[1];
        Runnable choose = () -> {
            chosen[0] = list.getSelectedValue();
            dialog.dispose();
        };
        query.getDocument().addDocumentListener(new DocumentListener() {
            private void filter() {
                model.clear();
                model.addAll(Filter.matching(query.getText(), options));
                list.setSelectedIndex(0);
            }

            @Override
            public void insertUpdate(DocumentEvent event) {
                filter();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                filter();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                filter();
            }
        });
        query.addActionListener(event -> choose.run());
        bind(query, "DOWN", () -> list.setSelectedIndex(Math.min(list.getSelectedIndex() + 1, model.size() - 1)));
        bind(query, "UP", () -> list.setSelectedIndex(Math.max(list.getSelectedIndex() - 1, 0)));
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) choose.run();
            }
        });
        var content = new JPanel(new BorderLayout(4, 4));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.add(query, BorderLayout.PAGE_START);
        content.add(new JScrollPane(list), BorderLayout.CENTER);
        dialog.setContentPane(content);
        closeOnEscape(dialog);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return Optional.ofNullable(chosen[0]);
    }

    @Override
    public Optional<String> line(String question, String initial) {
        return Optional.ofNullable((String) JOptionPane.showInputDialog(owner, question, null, JOptionPane.QUESTION_MESSAGE, null, null, initial));
    }

    @Override
    public void tell(String title, List<String> lines) {
        var text = new JTextArea(String.join("\n", lines), Math.min(lines.size(), 24), 80);
        text.setEditable(false);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JOptionPane.showMessageDialog(owner, new JScrollPane(text), title, JOptionPane.PLAIN_MESSAGE);
    }

    private static GridBagConstraints at(int column, int row) {
        var constraints = new GridBagConstraints();
        constraints.gridx = column;
        constraints.gridy = row;
        constraints.anchor = GridBagConstraints.LINE_START;
        constraints.fill = column == 1 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        constraints.weightx = column;
        constraints.insets = new Insets(3, 3, 3, 3);
        return constraints;
    }

    private static void bind(JComponent component, String key, Runnable action) {
        component.getInputMap().put(KeyStroke.getKeyStroke(key), key);
        component.getActionMap().put(key, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                action.run();
            }
        });
    }

    private static void closeOnEscape(JDialog dialog) {
        dialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        dialog.getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                dialog.dispose();
            }
        });
    }
}
