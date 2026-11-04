using System.Windows;

namespace Daybook.Windows;

public partial class DriveSettingsWindow : Window
{
    public string ClientId => ClientIdBox.Text.Trim();

    public DriveSettingsWindow(string? clientId)
    {
        InitializeComponent();
        ClientIdBox.Text = clientId ?? "";
        Loaded += (_, _) => ClientIdBox.Focus();
    }

    private void Save_Click(object sender, RoutedEventArgs e)
    {
        if (!ClientId.EndsWith(".apps.googleusercontent.com", StringComparison.Ordinal))
        {
            MessageBox.Show("Проверь OAuth Client ID. Он должен оканчиваться на .apps.googleusercontent.com.",
                "Daybook", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }

        DialogResult = true;
    }
}