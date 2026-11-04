using System.Windows;
using System.Collections.ObjectModel;
using System.Windows.Threading;
using Daybook.Windows.Models;
using Daybook.Windows.Services;

namespace Daybook.Windows;

public partial class MainWindow : Window
{
    private readonly LocalStore _store = new();
    private readonly GoogleOAuthPkceClient _oauth = new();
    private readonly DriveSyncCoordinator _driveSync;
    private readonly DispatcherTimer _syncDebounceTimer = new() { Interval = TimeSpan.FromMilliseconds(900) };
    private readonly DispatcherTimer _syncPollTimer = new() { Interval = TimeSpan.FromSeconds(30) };
    private bool _syncRunning;
    private bool _syncAgain;
    private DaybookDocument _document = new();
    public ObservableCollection<DaybookEntry> VisibleEntries { get; } = [];
    private string _section = "all";
    private string _taskFilter = "all";

    public MainWindow()
    {
        InitializeComponent();
        _driveSync = new DriveSyncCoordinator(_store);
        _syncDebounceTimer.Tick += async (_, _) =>
        {
            _syncDebounceTimer.Stop();
            await RunDriveSyncAsync(quiet: true, allowInteractive: false);
        };
        _syncPollTimer.Tick += async (_, _) =>
        {
            if (IsActive) await RunDriveSyncAsync(quiet: true, allowInteractive: false);
        };
        _syncPollTimer.Start();
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
            (string.IsNullOrWhiteSpace(query) ||
             item.Title.Contains(query, StringComparison.CurrentCultureIgnoreCase) ||
             item.Body.Contains(query, StringComparison.CurrentCultureIgnoreCase)) &&
            (!item.IsTask || _taskFilter == "all" ||
             (_taskFilter == "active" && !item.Done) ||
             (_taskFilter == "done" && item.Done)));

        if (TaskFilter is not null)
            TaskFilter.Visibility = _section == "notes" ? Visibility.Collapsed : Visibility.Visible;

        foreach (var item in source.OrderBy(item => item.IsTask && item.Done)
                     .ThenBy(item => item.IsTask ? item.Due ?? "9999" : "9999")
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

    private void ShowAll_Click(object sender, RoutedEventArgs e)
    {
        _section = "all";
        Refresh();
    }

    private async void DriveSettings_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var currentClientId = await _oauth.GetConfiguredClientIdAsync();
            if (string.IsNullOrWhiteSpace(currentClientId))
            {
                var dialog = new DriveSettingsWindow(currentClientId) { Owner = this };
                if (dialog.ShowDialog() != true) return;
                await _oauth.ConfigureClientIdAsync(dialog.ClientId);
            }

            await RunDriveSyncAsync(quiet: false, allowInteractive: true);
        }
        catch (Exception ex)
        {
            DriveStatus.Text = ex.Message;
            MessageBox.Show(ex.Message, "Daybook · Google Drive",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void ScheduleDriveSync()
    {
        _syncDebounceTimer.Stop();
        _syncDebounceTimer.Start();
    }

    private async Task RunDriveSyncAsync(bool quiet, bool allowInteractive)
    {
        if (_syncRunning)
        {
            _syncAgain = true;
            return;
        }

        _syncRunning = true;
        try
        {
            do
            {
                _syncAgain = false;
                if (!allowInteractive && !await _oauth.HasSavedRefreshTokenAsync())
                    return;

                DriveStatus.Text = "Синхронизация с Google Drive…";
                var accessToken = await _oauth.GetAccessTokenAsync(allowInteractive: allowInteractive);
                var outcome = await _driveSync.SyncAsync(accessToken);
                DriveStatus.Text = outcome.Message;
                await LoadAsync();

                if (!quiet)
                {
                    var details = outcome.Conflicts is { Count: > 0 }
                        ? "\\nЗаписи: " + string.Join(", ", outcome.Conflicts)
                        : "";
                    MessageBox.Show(outcome.Message + details, "Daybook · Google Drive",
                        MessageBoxButton.OK,
                        outcome.Status == "conflict" ? MessageBoxImage.Warning : MessageBoxImage.Information);
                }

                allowInteractive = false;
            } while (_syncAgain);
        }
        catch (Exception ex)
        {
            DriveStatus.Text = ex.Message;
            if (!quiet)
                MessageBox.Show(ex.Message, "Daybook · Google Drive",
                    MessageBoxButton.OK, MessageBoxImage.Error);
        }
        finally
        {
            _syncRunning = false;
        }
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

    private void TaskFilter_SelectionChanged(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (TaskFilter?.SelectedItem is System.Windows.Controls.ComboBoxItem selected &&
            selected.Tag is string filter)
        {
            _taskFilter = filter;
            Refresh();
        }
    }

    private async Task PersistAsync()
    {
        await _store.SaveAsync(_document);
        ScheduleDriveSync();
    }

    private async void AddTask_Click(object sender, RoutedEventArgs e) => await EditEntryAsync(true);
    private async void AddNote_Click(object sender, RoutedEventArgs e) => await EditEntryAsync(false);

    private async Task EditEntryAsync(bool isTask, DaybookEntry? entry = null)
    {
        var dialog = new EntryEditorWindow(isTask, entry) { Owner = this };
        if (dialog.ShowDialog() != true) return;

        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        if (entry is null)
        {
            entry = new DaybookEntry
            {
                Created = now,
                Updated = now,
                Title = dialog.EntryTitle,
                Body = dialog.EntryBody,
                Due = isTask ? dialog.EntryDue : null,
                Priority = isTask ? dialog.EntryPriority : null
            };
            (isTask ? _document.Tasks : _document.Notes).Insert(0, entry);
        }
        else
        {
            entry.Title = dialog.EntryTitle;
            entry.Body = dialog.EntryBody;
            entry.Updated = now;
            if (isTask)
            {
                entry.Due = dialog.EntryDue;
                entry.Priority = dialog.EntryPriority;
            }
        }

        await PersistAsync();
        Refresh();
    }

    private async void EntryList_MouseDoubleClick(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        if (EntryList.SelectedItem is not DaybookEntry item) return;
        await EditEntryAsync(item.IsTask, item);
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
