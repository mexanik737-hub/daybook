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
        VisibleEntries.Clear();
        foreach (var item in _document.Tasks.OrderBy(x => x.Done).ThenBy(x => x.Due)
                     .Concat(_document.Notes.OrderByDescending(x => x.Updated)))
            VisibleEntries.Add(item);
        EntryList.ItemsSource = VisibleEntries;
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
