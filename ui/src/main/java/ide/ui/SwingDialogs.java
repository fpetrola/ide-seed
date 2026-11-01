package ide.ui;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JWindow;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The dialogs people see, as IDEs show them: a popup with no frame at the caret or at the top of the window, that
 * filters as people type, takes Enter and goes away with Escape or a click elsewhere; and a dialog for a refactoring's
 * form, with its preview.
 */
final class SwingDialogs implements Dialogs {

    private final JFrame owner;

    SwingDialogs(JFrame owner) {
        this.owner = owner;
    }

    @Override
    public Optional<String> choose(String title, List<Option> options, Point at) {
        var model = new DefaultListModel<Option>();
        model.addAll(options);
        var list = new JList<>(model);
        list.setCellRenderer(new OptionRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(Math.max(1, Math.min(options.size(), 14)));
        list.setSelectedIndex(0);
        var query = new JTextField();
        var texts = options.stream().map(Option::text).toList();
        query.getDocument().addDocumentListener(changes(() -> {
            model.clear();
            var matching = Filter.matching(query.getText(), texts);
            options.stream().filter(option -> matching.contains(option.text())).forEach(model::addElement);
            list.setSelectedIndex(0);
        }));
        var popup = new Popup(at == null);
        bind(query, "DOWN", () -> list.setSelectedIndex(Math.min(list.getSelectedIndex() + 1, model.size() - 1)));
        bind(query, "UP", () -> list.setSelectedIndex(Math.max(list.getSelectedIndex() - 1, 0)));
        query.addActionListener(event -> popup.close(list.getSelectedValue() == null ? null : list.getSelectedValue().text()));
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && list.getSelectedValue() != null) popup.close(list.getSelectedValue().text());
            }
        });
        var scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        if (at == null) scroll.setPreferredSize(new Dimension(620, scroll.getPreferredSize().height));
        var content = new JPanel(new BorderLayout());
        content.add(heading(title, query, at == null), BorderLayout.PAGE_START);
        content.add(scroll, BorderLayout.CENTER);
        return popup.show(content, query, at);
    }

    @Override
    public Optional<String> line(String title, String initial, Point at) {
        var field = new JTextField(initial, Math.max(16, initial.length() + 4));
        field.selectAll();
        var popup = new Popup(at == null);
        field.addActionListener(event -> popup.close(field.getText()));
        var content = new JPanel(new BorderLayout());
        content.add(label(title), BorderLayout.PAGE_START);
        content.add(field, BorderLayout.CENTER);
        return popup.show(content, field, at);
    }

    @Override
    public void tell(String title, List<String> lines, Point at) {
        var list = new JList<>(lines.toArray(String[]::new));
        list.setFont(new Font("JetBrains Mono", Font.PLAIN, 12));
        list.setVisibleRowCount(Math.min(lines.size(), 24));
        var content = new JPanel(new BorderLayout());
        content.add(label(title), BorderLayout.PAGE_START);
        content.add(new JScrollPane(list), BorderLayout.CENTER);
        new Popup(at == null).show(content, list, at);
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
            var label = new JLabel(capitalized(param.name()) + ":");
            label.setToolTipText(param.description());
            input.setToolTipText(param.description());
            fields.add(label, at(0, row));
            fields.add(input, at(1, row++));
        }
        var diff = new DiffPane();
        var diffScroll = new JScrollPane(diff);
        diffScroll.setPreferredSize(new Dimension(820, 300));
        diffScroll.setBorder(BorderFactory.createTitledBorder("Preview"));
        Map<String, Object>[] result = new Map[1];
        Supplier<Map<String, Object>> values = () -> {
            var read = new LinkedHashMap<String, Object>();
            readers.forEach((name, reader) -> read.put(name, reader.get()));
            return read;
        };
        var show = new JButton("Preview");
        show.addActionListener(event -> diff.show(preview.apply(values.get())));
        var refactor = new JButton("Refactor");
        refactor.addActionListener(event -> {
            result[0] = values.get();
            dialog.dispose();
        });
        var cancel = new JButton("Cancel");
        cancel.addActionListener(event -> dialog.dispose());
        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(show);
        buttons.add(cancel);
        buttons.add(refactor);
        var content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(BorderFactory.createEmptyBorder(14, 14, 10, 14));
        content.add(fields, BorderLayout.PAGE_START);
        content.add(diffScroll, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.PAGE_END);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(refactor);
        dialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        dialog.getRootPane().getActionMap().put("close", action(dialog::dispose));
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return Optional.ofNullable(result[0]);
    }

    /** A window with no frame that waits, as a menu does, until it answers or goes away. */
    private final class Popup {

        private final boolean search;
        private final JWindow window = new JWindow(owner);
        private final java.awt.SecondaryLoop loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        private String answer;
        private boolean closed;

        Popup(boolean search) {
            this.search = search;
        }

        Optional<String> show(JComponent content, JComponent focus, Point at) {
            content.setBorder(BorderFactory.createLineBorder(border()));
            bind(content, "ESCAPE", () -> close(null));
            window.setContentPane(content);
            window.setFocusableWindowState(true);
            window.pack();
            if (at != null) {
                window.setLocation(at);
            } else {
                window.setLocation(owner.getX() + (owner.getWidth() - window.getWidth()) / 2, owner.getY() + owner.getHeight() / 6);
            }
            window.addWindowFocusListener(new WindowAdapter() {
                @Override
                public void windowLostFocus(WindowEvent event) {
                    close(null);
                }
            });
            window.setVisible(true);
            focus.requestFocusInWindow();
            loop.enter();
            return Optional.ofNullable(answer);
        }

        void close(String answer) {
            if (closed) return;
            closed = true;
            this.answer = answer;
            window.dispose();
            loop.exit();
        }
    }

    /** The heading of a list: a search field for a search, a title over a field for a popup at the caret. */
    private static JComponent heading(String title, JTextField query, boolean search) {
        var heading = new JPanel(new BorderLayout());
        heading.add(label(title), BorderLayout.PAGE_START);
        query.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, border()),
                BorderFactory.createEmptyBorder(search ? 6 : 2, 8, search ? 6 : 2, 8)));
        if (search) query.setFont(query.getFont().deriveFont(15f));
        heading.add(query, BorderLayout.CENTER);
        return heading;
    }

    private static JLabel label(String title) {
        var label = new JLabel(title);
        label.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        label.setForeground(new Color(0x8C8C8C));
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 12f));
        return label;
    }

    private static Color border() {
        var color = UIManager.getColor("Component.borderColor");
        return color == null ? new Color(0x515151) : color;
    }

    /** An option: its icon, its name, and in gray where it is or what it is about. */
    private static final class OptionRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            var option = (Option) value;
            var text = option.text();
            var hash = text.indexOf('#');
            String name;
            String where;
            if (hash >= 0) {
                name = text.substring(hash + 1);
                where = text.substring(0, hash);
            } else if (option.kind().matches("CLASS|INTERFACE|ENUM") && text.contains(".")) {
                name = text.substring(text.lastIndexOf('.') + 1);
                where = text.substring(0, text.lastIndexOf('.'));
            } else {
                name = text;
                where = option.remark();
            }
            var html = "<html>" + escape(name) + (where.isEmpty() ? "" : "&nbsp;&nbsp;<font color='#808080'>" + escape(where) + "</font>") + "</html>";
            super.getListCellRendererComponent(list, html, index, selected, focus);
            setIcon(Icons.of(option.kind()));
            setIconTextGap(6);
            setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 10));
            return this;
        }

        private static String escape(String text) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    private static String capitalized(String name) {
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static GridBagConstraints at(int column, int row) {
        var constraints = new GridBagConstraints();
        constraints.gridx = column;
        constraints.gridy = row;
        constraints.anchor = GridBagConstraints.LINE_START;
        constraints.fill = column == 1 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        constraints.weightx = column;
        constraints.insets = new Insets(4, 4, 4, 4);
        return constraints;
    }

    private static DocumentListener changes(Runnable changed) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                changed.run();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                changed.run();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                changed.run();
            }
        };
    }

    private static void bind(JComponent component, String key, Runnable action) {
        component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), key);
        component.getActionMap().put(key, action(action));
    }

    private static AbstractAction action(Runnable action) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                action.run();
            }
        };
    }
}
