using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using Daybook.Windows.Models;

namespace Daybook.Windows;

public partial class EntryEditorWindow : Window
{
    public string EntryTitle => TitleBox.Text.Trim();
    public string EntryBody => BodyBox.Text;
    public string? EntryDue => DuePicker.SelectedDate?.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
    public string EntryPriority => (PriorityBox.SelectedItem as ComboBoxItem)?.Tag?.ToString() ?? "normal";

    public EntryEditorWindow(bool isTask, DaybookEntry? entry = null)
    {
        InitializeComponent();
        Title = isTask ? "Задача — Daybook" : "Заметка — Daybook";
        DialogTitle.Text = entry is null
            ? (isTask ? "Новая задача" : "Новая заметка")
            : (isTask ? "Редактировать задачу" : "Редактировать заметку");
        DialogEyebrow.Text = entry is null ? "НОВАЯ ЗАПИСЬ" : "РЕДАКТИРОВАНИЕ";
        TaskFields.Visibility = isTask ? Visibility.Visible : Visibility.Collapsed;

        if (entry is not null)
        {
            TitleBox.Text = entry.Title;
            BodyBox.Text = entry.Body;
            if (isTask && DateTime.TryParseExact(entry.Due, "yyyy-MM-dd",
                    CultureInfo.InvariantCulture, DateTimeStyles.None, out var due))
                DuePicker.SelectedDate = due;

            foreach (ComboBoxItem option in PriorityBox.Items)
                if (string.Equals(option.Tag?.ToString(), entry.Priority ?? "normal", StringComparison.Ordinal))
                    PriorityBox.SelectedItem = option;
        }

        Loaded += (_, _) => TitleBox.Focus();
    }

    private void Save_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(EntryTitle) && string.IsNullOrWhiteSpace(EntryBody))
        {
            MessageBox.Show("Добавь название или текст записи.", "Daybook",
                MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }

        DialogResult = true;
    }
}