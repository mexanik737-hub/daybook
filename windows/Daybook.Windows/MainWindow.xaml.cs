using System.Windows;
using System.Collections.ObjectModel;
using Daybook.Windows.Models;
using Daybook.Windows.Services;

namespace Daybook.Windows;

public partial class MainWindow : Window
{
    private readonly LocalStore _store = new();
    private DaybookDocument _document = new();
    public ObservableCollection<DaybookEntry> VisibleEntries { get; } = [];
    private string _section = "all";

    public MainWindow()
    {
        InitializeComponent();
        Loaded += async (_, _) => await LoadAsync();
    }

    private async Task LoadAsync()
    {
        try
        {
            _document = await _store.LoadAsync();
            Refresh();
        }
        catch (Exception ex)
        {
            MessageBox.Show($"Не удалось открыть локальные данные: {ex.Message}", "Daybook",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void Refresh()
    {
        if (EntryList is null) return;
        VisibleEntries.Clear();
        var query = SearchBox?.Text?.Trim() ?? "";
        IEnumerable<DaybookEntry> source = [];

        if (_section is "all" or "tasks")
        {
            foreach (var task in _document.Tasks) task.IsTask = true;
            source = source.Concat(_document.Tasks);
        }
        if (_section is "all" or "notes")
        {
            foreach (var note in _document.Notes) note.IsTask = false;
            source = source.Concat(_document.Notes);
        }

        source = source.Where(item =>
            string.IsNullOrWhiteSpace(query) ||
            item.Title.Contains(query, StringComparison.CurrentCultureIgnoreCase) ||
            item.Body.Contains(query, StringComparison.CurrentCultureIgnoreCase));

        foreach (var item in source.OrderBy(item => item.IsTask && item.Done)
                     .ThenByDescending(item => item.Updated))
            VisibleEntries.Add(item);

        EntryList.ItemsSource = VisibleEntries;
        if (PageTitle is not null)
            PageTitle.Text = _section switch
            {
                "tasks" => "Задачи",
                "notes" => "Заметки",
                _ => "Всё важное — в одном месте."
            };
    }

    private void ShowTasks_Click(object sender, RoutedEventArgs e)
    {
        _section = "tasks";
        Refresh();
    }

    private void ShowNotes_Click(object sender, RoutedEventArgs e)
    {
        _section = "notes";
        Refresh();
    }

    private void SearchBox_TextChanged(object sender, System.Windows.Controls.TextChangedEventArgs e)
    {
        Refresh();
    }

    private Task PersistAsync() => _store.SaveAsync(_document);

    private async void AddTask_Click(object sender, RoutedEventArgs e) => await AddEntryAsync(true);
    private async void AddNote_Click(object sender, RoutedEventArgs e) => await AddEntryAsync(false);

    private async Task AddEntryAsync(bool task)
    {
        var title = Microsoft.VisualBasic.Interaction.InputBox("Название:", task ? "Новая задача" : "Новая заметка");
        if (string.IsNullOrWhiteSpace(title)) return;
        var body = Microsoft.VisualBasic.Interaction.InputBox("Текст (необязательно):", "Daybook");
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var entry = new DaybookEntry { Title = title.Trim(), Body = body, Created = now, Updated = now };
        (task ? _document.Tasks : _document.Notes).Insert(0, entry);
        await PersistAsync();
        Refresh();
    }

    private async void EntryList_MouseDoubleClick(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        if (EntryList.SelectedItem is not DaybookEntry item) return;
        var title = Microsoft.VisualBasic.Interaction.InputBox("Название:", "Изменить запись", item.Title);
        if (string.IsNullOrWhiteSpace(title)) return;
        var body = Microsoft.VisualBasic.Interaction.InputBox("Текст:", "Изменить запись", item.Body);
        item.Title = title.Trim();
        item.Body = body;
        item.Updated = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        await PersistAsync();
        Refresh();
    }

    private async void Delete_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not string id) return;
        var answer = MessageBox.Show("Удалить эту запись?", "Daybook", MessageBoxButton.YesNo, MessageBoxImage.Question);
        if (answer != MessageBoxResult.Yes) return;
        _document.Tasks.RemoveAll(x => x.Id == id);
        _document.Notes.RemoveAll(x => x.Id == id);
        await PersistAsync();
        Refresh();
    }

    private async void ToggleDone_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not string id) return;
        var item = _document.Tasks.FirstOrDefault(x => x.Id == id);
        if (item is null) return;
        item.Done = !item.Done;
        item.Updated = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        await PersistAsync();
        Refresh();
    }
}
