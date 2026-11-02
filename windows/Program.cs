using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Text.Json;
using System.Windows.Forms;

namespace AmpyDeskBridge;

internal static class Program
{
    [STAThread] private static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new BridgeForm());
    }
}

// Portable manual launcher; does not execute remote Windows commands.
internal sealed class BridgeForm : Form
{
    private readonly TextBox url = new();
    private readonly Label status = new();
    private readonly Button launch = new();

    public BridgeForm()
    {
        Text = "AmpyDeskGPT // Windows Bridge";
        Size = new Size(570, 340); MinimumSize = new Size(520, 320);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.FromArgb(7, 16, 29);
        ForeColor = Color.FromArgb(205, 246, 250);
        Font = new Font("Segoe UI", 10);
        Controls.Add(new Label {
            Text = "⬡  AMPYDESK GPT", Font = new Font("Segoe UI", 22, FontStyle.Bold),
            ForeColor = Color.FromArgb(61, 234, 236),
            Location = new Point(26, 23), Size = new Size(510, 47)
        });
        Controls.Add(new Label {
            Text = "AMPYBIOS  •  PORTABLE WINDOWS BRIDGE",
            ForeColor = Color.FromArgb(251, 203, 108),
            Location = new Point(29, 76), Size = new Size(500, 24)
        });
        Controls.Add(new Label {
            Text = "Paste the complete pairing link shown on your Pixel:",
            Location = new Point(29, 123), Size = new Size(485, 25)
        });
        url.Location = new Point(29, 155); url.Size = new Size(492, 30);
        url.Font = new Font("Consolas", 10);
        url.PlaceholderText = "http://192.168.x.x:8777/?t=...";
        Controls.Add(url);
        launch.Text = "START AMPY DESKTOP";
        launch.Location = new Point(29, 199); launch.Size = new Size(225, 38);
        launch.BackColor = Color.FromArgb(27, 85, 102);
        launch.ForeColor = Color.White; launch.FlatStyle = FlatStyle.Flat;
        launch.FlatAppearance.BorderColor = Color.FromArgb(61, 234, 236);
        launch.Click += async (_, _) => await LaunchAsync();
        Controls.Add(launch);
        status.Location = new Point(29, 248); status.Size = new Size(505, 44);
        status.ForeColor = Color.FromArgb(145, 187, 204);
        status.Text = "No installer • Manual first launch • LAN-only";
        Controls.Add(status);
    }

    private static bool PrivateNetwork(Uri uri)
    {
        if (uri.Scheme != Uri.UriSchemeHttp || uri.Port != 8777) return false;
        if (!IPAddress.TryParse(uri.Host, out var addr) ||
            addr.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork) return false;
        byte[] b = addr.GetAddressBytes();
        return b[0] == 10 || b[0] == 127 ||
               (b[0] == 172 && b[1] >= 16 && b[1] <= 31) ||
               (b[0] == 192 && b[1] == 168);
    }

    private async System.Threading.Tasks.Task LaunchAsync()
    {
        launch.Enabled = false;
        try
        {
            if (!Uri.TryCreate(url.Text.Trim(), UriKind.Absolute, out var uri) ||
                !PrivateNetwork(uri) || !uri.Query.Contains("t=", StringComparison.Ordinal))
                throw new InvalidOperationException("Use the private LAN URL including ?t= shown on your Pixel.");

            using HttpClient client = new() { Timeout = TimeSpan.FromSeconds(8) };
            Uri statusUri = new(uri.GetLeftPart(UriPartial.Authority) + "/api/status" + uri.Query);
            var response = await client.GetAsync(statusUri);
            response.EnsureSuccessStatusCode();
            using JsonDocument doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
            bool fullscreen = doc.RootElement.GetProperty("mode").GetString() == "fullscreen";
            var manufacturer = doc.RootElement.GetProperty("name").GetString();
            status.Text = "Connection OK • " + manufacturer + " • " + (fullscreen ? "Fullscreen" : "Windowed");
            string edge1 = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86),
                "Microsoft", "Edge", "Application", "msedge.exe");
            string edge2 = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
                "Microsoft", "Edge", "Application", "msedge.exe");
            string edge = File.Exists(edge1) ? edge1 : edge2;
            if (File.Exists(edge))
                Process.Start(new ProcessStartInfo(edge) {
                    UseShellExecute = false,
                    Arguments = (fullscreen ? "--start-fullscreen " : "") + "--app=" + '"' + uri.AbsoluteUri + '"'
                });
            else {
                Process.Start(new ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true });
                status.Text = "Opened default browser; app-style Edge mode not available.";
            }
        }
        catch (Exception ex) { status.Text = "FAILED • " + ex.Message; }
        finally { launch.Enabled = true; }
    }
}
