// PKHeX Android port - user interface
// Copyright (C) 2026 PKHeX contributors
//
// This program is free software: you can redistribute it and/or modify it under the terms of the
// GNU General Public License as published by the Free Software Foundation, either version 3 of
// the License, or (at your option) any later version. See LICENSE.

using Android.Content;
using Android.Content.PM;
using Android.Runtime;
using Android.Text;
using Android.Views;
using Android.Widget;
using PKHeX.Core;
using AColor = Android.Graphics.Color;
using Canvas = Android.Graphics.Canvas;
using Paint = Android.Graphics.Paint;
using PaintFlags = Android.Graphics.PaintFlags;
using RectF = Android.Graphics.RectF;

namespace PKHeXAndroid;

/// <summary>
/// One storage slot, drawn as a coloured tile: the colours come from the species' types and the
/// text shows its name and level. Stands in for the picture a slot has in the desktop program.
/// </summary>
public sealed class SlotView : View
{
    // One colour per type index (an original palette, not taken from any game).
    private static readonly int[] TypeColors =
    [
        0x8A8D7A, 0xB5503C, 0x7F9BD8, 0x8E55A0, 0xC2A565, 0x9E8A4A, 0x8FA63A, 0x65588F, 0x9FA3B3,
        0xD9773A, 0x4F86D6, 0x5FA955, 0xE0BE3C, 0xD9628A, 0x7FC9CC, 0x5E48C9, 0x5A4A45, 0xD99AC4,
    ];

    private readonly Paint paint = new(PaintFlags.AntiAlias);
    private readonly RectF rect = new();
    private string line1 = "", line2 = "", marks = "";
    private int color1 = 0x2C2F33, color2 = 0x2C2F33;
    private bool empty = true;

    public bool Highlighted { get; set; }

    public SlotView(Context context) : base(context) { }

    public void Bind(PKM? pk)
    {
        empty = pk is null || pk.Species == 0;
        line1 = line2 = marks = "";
        color1 = color2 = 0x2C2F33;
        if (!empty)
        {
            var names = GameInfo.Strings.Species;
            line1 = pk!.Species < names.Count ? names[pk.Species] : $"#{pk.Species}";
            line2 = pk.IsEgg ? "ovo" : $"Nv. {pk.CurrentLevel}";
            try
            {
                var info = pk.PersonalInfo;
                color1 = TypeColors[info.Type1 % TypeColors.Length];
                color2 = TypeColors[info.Type2 % TypeColors.Length];
                if (pk.IsShiny)
                    marks += "★";
                if (!new LegalityAnalysis(pk).Valid)
                    marks += "⚠";
            }
            catch (Exception)
            {
                marks += "?";
            }
        }
        Invalidate();
    }

    private static AColor Rgb(int rgb, int alpha = 255) => new((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, alpha);

    private void DrawFitted(Canvas canvas, string text, float cx, float y, float size, float maxWidth)
    {
        paint.TextSize = size;
        while (paint.TextSize > size * 0.55f && paint.MeasureText(text) > maxWidth)
            paint.TextSize -= 1f;
        canvas.DrawText(text, cx, y, paint);
    }

    protected override void OnDraw(Canvas canvas)
    {
        base.OnDraw(canvas);
        float w = Width, h = Height, m = Math.Max(2f, w * 0.03f);
        if (w <= 0 || h <= 0)
            return;

        paint.SetStyle(Paint.Style.Fill);
        paint.Color = Rgb(color1);
        canvas.DrawRect(m, m, w / 2f, h - m, paint);
        paint.Color = Rgb(color2);
        canvas.DrawRect(w / 2f, m, w - m, h - m, paint);

        if (!empty)
        {
            // Dark band behind the text keeps it readable on any colour.
            paint.Color = Rgb(0x000000, 110);
            canvas.DrawRect(m, h * 0.18f, w - m, h * 0.92f, paint);
            paint.Color = AColor.White;
            paint.TextAlign = Paint.Align.Center;
            float size = Math.Min(h * 0.27f, w * 0.26f);
            DrawFitted(canvas, line1, w / 2f, h * 0.50f, size, w - m * 4f);
            DrawFitted(canvas, line2, w / 2f, h * 0.82f, size * 0.85f, w - m * 4f);
            if (marks.Length > 0)
            {
                paint.TextAlign = Paint.Align.Right;
                paint.Color = Rgb(0xFFE066);
                paint.TextSize = size * 0.8f;
                canvas.DrawText(marks, w - m * 2f, m + size * 0.75f, paint);
            }
        }

        if (Highlighted)
        {
            paint.SetStyle(Paint.Style.Stroke);
            paint.StrokeWidth = m * 1.2f;
            paint.Color = AColor.White;
            rect.Set(m, m, w - m, h - m);
            canvas.DrawRect(rect, paint);
        }
    }
}

/// <summary>
/// The whole app is this one activity. Each screen is a method that builds a view; a small
/// stack of those methods gives the Back button something to return to.
/// </summary>
[Activity(
    Label = "@string/app_name",
    MainLauncher = true,
    Theme = "@android:style/Theme.Material.NoActionBar",
    ConfigurationChanges = ConfigChanges.Orientation | ConfigChanges.ScreenSize | ConfigChanges.KeyboardHidden)]
public class MainActivity : Activity
{
    private const int RequestOpen = 1;
    private const int RequestExport = 2;

    // Static: the loaded save must survive the activity being recreated by the system.
    private static SaveFile? sav;
    private static string savName = "";

    // The Pokémon loaded in the editor (a copy; slots only change through "Colocar aqui").
    private static PKM? editing;
    private static string? startupCrash;

    private readonly Stack<Func<View>> history = new();
    private Func<View>? current;
    private int currentBox;
    private int currentTab;
    private Func<string?>? applyTab;

    // ------------------------------------------------------------------ lifecycle / navigation
    protected override void OnCreate(Bundle? savedInstanceState)
    {
        base.OnCreate(savedInstanceState);
        InstallCrashReporter();
        Navigate(Home);
        if (startupCrash != null)
        {
            Message("O app fechou na última vez", "Envie este texto para quem cuida do app:\n\n" + startupCrash);
            startupCrash = null;
        }
    }

    private string CrashFile => System.IO.Path.Combine(FilesDir!.AbsolutePath, "crash.txt");

    /// <summary>Saves the details of a fatal error so they can be shown the next time the app opens.</summary>
    private void InstallCrashReporter()
    {
        var path = CrashFile;
        try
        {
            if (File.Exists(path))
            {
                startupCrash = File.ReadAllText(path);
                if (startupCrash.Length > 3000)
                    startupCrash = startupCrash[..3000];
                File.Delete(path);
            }
        }
        catch (Exception)
        {
            // Not being able to read an old report must never stop the app from opening.
        }
        if (crashReporterInstalled)
            return;
        crashReporterInstalled = true;
        AndroidEnvironment.UnhandledExceptionRaiser += (_, e) => WriteCrash(path, e.Exception);
        AppDomain.CurrentDomain.UnhandledException += (_, e) => WriteCrash(path, e.ExceptionObject as Exception);
    }

    private static bool crashReporterInstalled;

    private static void WriteCrash(string path, Exception? ex)
    {
        try { File.WriteAllText(path, ex?.ToString() ?? "erro desconhecido"); }
        catch (Exception) { }
    }

    public override void OnConfigurationChanged(Android.Content.Res.Configuration newConfig)
    {
        base.OnConfigurationChanged(newConfig);
        Refresh(); // the main screen arranges itself differently in portrait and landscape
    }

    /// <summary>Builds a screen, showing the error instead of closing the app if something goes wrong.</summary>
    private View Safe(Func<View> screen)
    {
        try
        {
            return screen();
        }
        catch (Exception ex)
        {
            var col = Column();
            col.AddView(Label("Erro ao montar esta tela", 20f), Fill());
            var text = Label(ex.ToString(), 12f);
            text.SetTextIsSelectable(true);
            col.AddView(text, Fill());
            col.AddView(Btn("Voltar ao início", () => { sav = null; editing = null; GoHome(); }), Fill());
            return Scrollable(col);
        }
    }

    private void Navigate(Func<View> screen)
    {
        if (current != null)
            history.Push(current);
        current = screen;
        SetContentView(Safe(screen));
    }

    /// <summary>Rebuilds the screen that is showing, after its data changed.</summary>
    private void Refresh()
    {
        if (current != null)
            SetContentView(Safe(current));
    }

    private void GoBack()
    {
        if (history.Count == 0)
        {
            Finish();
            return;
        }
        current = history.Pop();
        SetContentView(Safe(current));
    }

    private void GoHome()
    {
        history.Clear();
        current = Home;
        SetContentView(Safe(Home));
    }

#pragma warning disable CS0672, CS0618, CA1422 // OnBackPressed is the simplest hook that works on every supported version
    public override void OnBackPressed() => GoBack();
#pragma warning restore CS0672, CS0618, CA1422

    // ------------------------------------------------------------------ small view helpers
    private int Dp(float value) => (int)(value * Resources!.DisplayMetrics!.Density + 0.5f);

    private static LinearLayout.LayoutParams Fill() =>
        new(ViewGroup.LayoutParams.MatchParent, ViewGroup.LayoutParams.WrapContent);

    private static LinearLayout.LayoutParams Weighted() =>
        new(0, ViewGroup.LayoutParams.WrapContent, 1f);

    private LinearLayout Column()
    {
        var col = new LinearLayout(this) { Orientation = Orientation.Vertical };
        col.SetPadding(Dp(16), Dp(16), Dp(16), Dp(16));
        return col;
    }

    private LinearLayout Row() => new(this) { Orientation = Orientation.Horizontal };

    private TextView Label(string text, float size = 15f)
    {
        var tv = new TextView(this) { Text = text };
        tv.SetTextSize(Android.Util.ComplexUnitType.Sp, size);
        tv.SetPadding(0, Dp(6), 0, Dp(2));
        return tv;
    }

    private Button Btn(string text, Action onClick)
    {
        var b = new Button(this) { Text = text };
        b.SetAllCaps(false);
        b.Click += (_, _) => onClick();
        return b;
    }

    private EditText Field(string text, bool numeric = false)
    {
        var e = new EditText(this) { Text = text };
        e.SetSingleLine(true);
        if (numeric)
            e.InputType = InputTypes.ClassNumber;
        return e;
    }

    private View Scrollable(View content)
    {
        var scroll = new ScrollView(this);
        scroll.AddView(content);
        return scroll;
    }

    private void Toast(string message) =>
        Android.Widget.Toast.MakeText(this, message, ToastLength.Long)!.Show();

    private void Message(string title, string message) =>
        new AlertDialog.Builder(this)
            .SetTitle(title)!
            .SetMessage(message)!
            .SetPositiveButton("OK", (_, _) => { })!
            .Show();

    private static int ParseInt(string? text, int fallback) =>
        int.TryParse(text?.Trim(), out var v) ? v : fallback;

    // ------------------------------------------------------------------ home
    private View Home()
    {
        var col = Column();
        col.AddView(Label("PKHeX para Android", 22f), Fill());

        var s = sav;
        if (s is null)
        {
            col.AddView(Label(
                "Editor de saves baseado no núcleo oficial do PKHeX.\n\n" +
                "Abra o arquivo de save do jogo (por exemplo .sav ou .dsv). " +
                "O arquivo original nunca é alterado: as mudanças só valem quando você exporta uma cópia."), Fill());
            col.AddView(Btn("Abrir arquivo de save", PickSave), Fill());
            return Scrollable(col);
        }

        return Workspace(s);
    }

    // ------------------------------------------------------------------ workspace (editor + storage, like the desktop program)
    private View Workspace(SaveFile s)
    {
        bool landscape = Resources!.Configuration!.Orientation == Android.Content.Res.Orientation.Landscape;
        var root = new LinearLayout(this) { Orientation = Orientation.Vertical };
        root.SetPadding(Dp(6), Dp(6), Dp(6), Dp(6));

        // Menu bar.
        var bar = Row();
        bar.AddView(Btn("Abrir", PickSave));
        bar.AddView(Btn("Exportar", ExportSave));
        bar.AddView(Btn("Treinador", () => TrainerDialog(s)));
        bar.AddView(Btn("Sobre o save", () => Message("Save", SaveInfo(s))));
        var barScroll = new HorizontalScrollView(this);
        barScroll.AddView(bar);
        root.AddView(barScroll, Fill());
        root.AddView(Label($"{GameInfo.GetVersionName(s.Version)} · {s.OT} · {savName}" + (s.State.Edited ? "  (alterado)" : ""), 12f), Fill());

        var body = new LinearLayout(this) { Orientation = landscape ? Orientation.Horizontal : Orientation.Vertical };
        var editor = EditorPanel(s);
        var storage = StoragePanel(s);
        if (landscape)
        {
            body.AddView(editor, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MatchParent, 1f));
            body.AddView(storage, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MatchParent, 1f));
        }
        else
        {
            body.AddView(editor, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MatchParent, 0, 1f));
            body.AddView(storage, Fill());
        }
        root.AddView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MatchParent, 0, 1f));
        return root;
    }

    private static string SaveInfo(SaveFile s) =>
        $"Arquivo: {savName}\n" +
        $"Jogo: {GameInfo.GetVersionName(s.Version)} (geração {s.Generation})\n" +
        $"Treinador: {s.OT}\n" +
        $"ID: {s.DisplayTID}\n" +
        $"Dinheiro: {s.Money}\n" +
        $"Tempo de jogo: {s.PlayedHours}h {s.PlayedMinutes:00}min\n" +
        $"Caixas: {s.BoxCount} · Equipe: {(s.HasParty ? s.PartyCount : 0)}\n" +
        $"Verificação do arquivo: {(s.ChecksumsValid ? "ok" : "com erros")}" +
        (s.State.Edited ? "\n\nHá alterações ainda não exportadas." : "");

    // ------------------------------------------------------------------ storage panel: box grid + party
    private View StoragePanel(SaveFile s)
    {
        var col = new LinearLayout(this) { Orientation = Orientation.Vertical };
        int tileH = Dp(46);

        if (s.HasBox && s.BoxCount > 0)
        {
            int count = s.BoxCount;
            currentBox = Math.Clamp(currentBox, 0, count - 1);
            int box = currentBox;
            var name = s is IBoxDetailNameRead named ? named.GetBoxName(box) : BoxDetailNameExtensions.GetDefaultBoxName(box);

            var header = Row();
            header.AddView(Btn("◀", () => { currentBox = (box + count - 1) % count; Rebuild(); }));
            var title = Label($"{name}  ({box + 1}/{count})", 15f);
            title.Gravity = GravityFlags.Center;
            header.AddView(title, Weighted());
            header.AddView(Btn("▶", () => { currentBox = (box + 1) % count; Rebuild(); }));
            col.AddView(header, Fill());

            // Six columns, like the desktop program.
            const int columns = 6;
            int slots = s.BoxSlotCount;
            for (int start = 0; start < slots; start += columns)
            {
                var row = Row();
                for (int c = 0; c < columns; c++)
                {
                    int slot = start + c;
                    var tile = new SlotView(this);
                    if (slot < slots)
                    {
                        var pk = s.GetBoxSlotAtIndex(box, slot);
                        tile.Bind(pk);
                        tile.Click += (_, _) => SlotMenu(tile, s, pk, isParty: false, box, slot);
                    }
                    row.AddView(tile, new LinearLayout.LayoutParams(0, tileH, 1f));
                }
                col.AddView(row, Fill());
            }
        }

        if (s.HasParty)
        {
            col.AddView(Label("Equipe", 13f), Fill());
            var row = Row();
            for (int i = 0; i < 6; i++)
            {
                int index = i;
                var tile = new SlotView(this);
                var pk = index < s.PartyCount ? s.GetPartySlotAtIndex(index) : null;
                tile.Bind(pk);
                tile.Click += (_, _) => SlotMenu(tile, s, pk, isParty: true, 0, index);
                row.AddView(tile, new LinearLayout.LayoutParams(0, tileH, 1f));
            }
            col.AddView(row, Fill());
        }

        col.AddView(Label("Toque num espaço: Ver, Colocar ou Apagar · ★ brilhante · ⚠ ilegal", 11f), Fill());
        return Scrollable(col);
    }

    /// <summary>The slot's menu: the same View / Set / Delete actions as the desktop program.</summary>
    private void SlotMenu(View anchor, SaveFile s, PKM? inSlot, bool isParty, int box, int slot)
    {
        bool occupied = inSlot is { Species: not 0 };
        var menu = new PopupMenu(this, anchor);
        if (occupied)
            menu.Menu!.Add(0, 1, 0, "Ver (carregar no editor)");
        if (editing is { Species: not 0 })
            menu.Menu!.Add(0, 2, 1, "Colocar aqui o Pokémon do editor");
        if (occupied)
            menu.Menu!.Add(0, 3, 2, "Apagar");
        if (menu.Menu!.Size() == 0)
        {
            Toast("Espaço vazio. Carregue um Pokémon no editor para colocar aqui.");
            return;
        }

        menu.MenuItemClick += (_, e) =>
        {
            try
            {
                switch (e.Item!.ItemId)
                {
                    case 1:
                        editing = inSlot!.Clone();
                        currentTab = 0;
                        Rebuild(applyFirst: false);
                        break;
                    case 2:
                        var error = applyTab?.Invoke();
                        if (error != null)
                        {
                            Toast(error);
                            return;
                        }
                        var pk = editing!.Clone();
                        if (isParty)
                        {
                            pk.ResetPartyStats();
                            s.SetPartySlotAtIndex(pk, Math.Min(slot, s.PartyCount));
                        }
                        else
                        {
                            s.SetBoxSlotAtIndex(pk, box, slot);
                        }
                        s.State.Edited = true;
                        Rebuild(applyFirst: false);
                        break;
                    case 3:
                        Confirm("Apagar este Pokémon?", () =>
                        {
                            if (isParty)
                            {
                                if (s.PartyCount <= 1)
                                {
                                    Toast("A equipe precisa de pelo menos um Pokémon.");
                                    return;
                                }
                                s.DeletePartySlot(slot);
                            }
                            else
                            {
                                s.SetBoxSlotAtIndex(s.BlankPKM, box, slot);
                            }
                            s.State.Edited = true;
                            Rebuild();
                        });
                        break;
                }
            }
            catch (Exception ex)
            {
                Message("Erro", ex.Message);
            }
        };
        menu.Show();
    }

    private void Confirm(string question, Action onYes) =>
        new AlertDialog.Builder(this)
            .SetMessage(question)!
            .SetPositiveButton("Sim", (_, _) => onYes())!
            .SetNegativeButton("Não", (_, _) => { })!
            .Show();

    /// <summary>Redraws the workspace, first copying what is typed in the editor into the Pokémon.</summary>
    private void Rebuild(bool applyFirst = true)
    {
        if (applyFirst)
        {
            var error = applyTab?.Invoke();
            if (error != null)
                Toast(error);
        }
        Refresh();
    }

    // ------------------------------------------------------------------ editor panel (tabs)
    private AutoCompleteTextView Picker(IReadOnlyList<ComboItem> items, int value)
    {
        var texts = new List<string>(items.Count);
        string selected = "";
        foreach (var item in items)
        {
            texts.Add(item.Text);
            if (item.Value == value)
                selected = item.Text;
        }
        var box = new AutoCompleteTextView(this)
        {
            Adapter = new ArrayAdapter<string>(this, global::Android.Resource.Layout.SimpleDropDownItem1Line, texts),
            Threshold = 1,
            Text = selected,
        };
        box.SetSingleLine(true);
        return box;
    }

    private Spinner Choice(IReadOnlyList<string> texts, int selected)
    {
        var adapter = new ArrayAdapter<string>(this, global::Android.Resource.Layout.SimpleSpinnerItem, texts.ToList());
        adapter.SetDropDownViewResource(global::Android.Resource.Layout.SimpleSpinnerDropDownItem);
        var spinner = new Spinner(this) { Adapter = adapter };
        if (selected >= 0 && selected < texts.Count)
            spinner.SetSelection(selected);
        return spinner;
    }

    /// <summary>Finds the list entry whose text was typed. Empty text counts as value 0 ("none").</summary>
    private static bool TryResolve(IReadOnlyList<ComboItem> items, string? typed, out int value)
    {
        value = 0;
        var text = typed?.Trim() ?? "";
        if (text.Length == 0)
            return true;
        foreach (var item in items)
        {
            if (string.Equals(item.Text, text, StringComparison.OrdinalIgnoreCase))
            {
                value = item.Value;
                return true;
            }
        }
        return false;
    }

    private EditText[] StatRow(LinearLayout parent, string title, ReadOnlySpan<int> values)
    {
        parent.AddView(Label(title), Fill());
        string[] names = ["HP", "Atq", "Def", "AtqE", "DefE", "Vel"];
        var labels = Row();
        var fields = Row();
        var result = new EditText[6];
        for (int i = 0; i < 6; i++)
        {
            var l = Label(names[i], 12f);
            l.Gravity = GravityFlags.Center;
            labels.AddView(l, Weighted());
            result[i] = Field(values[i].ToString(), numeric: true);
            result[i].Gravity = GravityFlags.Center;
            fields.AddView(result[i], Weighted());
        }
        parent.AddView(labels, Fill());
        parent.AddView(fields, Fill());
        return result;
    }

    private View EditorPanel(SaveFile s)
    {
        var col = new LinearLayout(this) { Orientation = Orientation.Vertical };
        applyTab = null;
        var pk = editing;
        if (pk is null || pk.Species == 0)
        {
            col.AddView(Label("Editor", 18f), Fill());
            col.AddView(Label("Toque num Pokémon das caixas ou da equipe e escolha \"Ver\" para carregar aqui."), Fill());
            return col;
        }

        // Header: preview tile, summary and the legality indicator.
        var header = Row();
        var preview = new SlotView(this);
        preview.Bind(pk);
        header.AddView(preview, new LinearLayout.LayoutParams(Dp(76), Dp(56)));
        bool legal;
        try { legal = new LegalityAnalysis(pk).Valid; }
        catch (Exception) { legal = false; }
        header.AddView(Btn(legal ? "✔ Legal" : "⚠ Ilegal", () =>
        {
            try
            {
                var error = applyTab?.Invoke();
                if (error != null)
                {
                    Toast(error);
                    return;
                }
                var analysis = new LegalityAnalysis(editing!);
                Message(analysis.Valid ? "Legal" : "Não é legal", analysis.Report());
                Refresh();
            }
            catch (Exception ex)
            {
                Message("Erro", ex.Message);
            }
        }));
        col.AddView(header, Fill());

        // Tabs.
        string[] tabNames = ["Principal", "Stats", "Golpes", "Origem"];
        var tabs = Row();
        for (int i = 0; i < tabNames.Length; i++)
        {
            int index = i;
            var tab = Btn(tabNames[i], () => { var e = applyTab?.Invoke(); if (e != null) { Toast(e); return; } currentTab = index; Refresh(); });
            tab.Enabled = index != currentTab;
            tabs.AddView(tab, Weighted());
        }
        col.AddView(tabs, Fill());

        var page = new LinearLayout(this) { Orientation = Orientation.Vertical };
        page.SetPadding(Dp(4), 0, Dp(4), Dp(8));
        applyTab = currentTab switch
        {
            1 => StatsTab(page, pk),
            2 => MovesTab(page, pk),
            3 => OriginTab(page, pk),
            _ => MainTab(page, pk),
        };
        col.AddView(Scrollable(page), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MatchParent, 0, 1f));
        return col;
    }

    /// <summary>Recalculates what depends on the edited values. Called at the end of every tab's apply.</summary>
    private static void Recalculate(PKM pk)
    {
        if (pk.PartyStatsPresent)
            pk.ResetPartyStats();
        pk.RefreshChecksum();
    }

    private Func<string?> MainTab(LinearLayout page, PKM pk)
    {
        var src = GameInfo.FilteredSources;

        page.AddView(Label("Espécie"), Fill());
        var species = Picker(src.Species, pk.Species);
        page.AddView(species, Fill());

        page.AddView(Label("Apelido (vazio = nome da espécie)"), Fill());
        var nickname = Field(pk.IsNicknamed ? pk.Nickname : "");
        page.AddView(nickname, Fill());

        page.AddView(Label("Nível"), Fill());
        var level = Field(pk.CurrentLevel.ToString(), numeric: true);
        page.AddView(level, Fill());

        Spinner? nature = null;
        var natures = src.Natures;
        if (pk.Format >= 3 && natures.Count > 0)
        {
            page.AddView(Label("Natureza"), Fill());
            int selected = 0;
            for (int i = 0; i < natures.Count; i++)
            {
                if (natures[i].Value == (int)pk.Nature)
                    selected = i;
            }
            nature = Choice(natures.Select(z => z.Text).ToList(), selected);
            page.AddView(nature, Fill());
        }

        Spinner? ability = null;
        if (pk.Format >= 3)
        {
            var abilities = src.GetAbilityList(pk.PersonalInfo);
            if (abilities.Count > 0)
            {
                page.AddView(Label("Habilidade"), Fill());
                int selected = 0;
                for (int i = 0; i < abilities.Count; i++)
                {
                    if (abilities[i].Value == pk.Ability)
                        selected = i;
                }
                ability = Choice(abilities.Select(z => z.Text).ToList(), selected);
                page.AddView(ability, Fill());
            }
        }

        page.AddView(Label("Item segurado (vazio = nenhum)"), Fill());
        var item = Picker(src.Items, pk.HeldItem);
        page.AddView(item, Fill());

        int formCount = Math.Max(1, (int)pk.PersonalInfo.FormCount);
        EditText? form = null;
        if (formCount > 1)
        {
            page.AddView(Label($"Forma (0 a {formCount - 1})"), Fill());
            form = Field(pk.Form.ToString(), numeric: true);
            page.AddView(form, Fill());
        }

        var shiny = new CheckBox(this) { Text = "Brilhante (shiny)", Checked = pk.IsShiny };
        page.AddView(shiny, Fill());

        page.AddView(Label("Amizade (0 a 255)"), Fill());
        var friendship = Field(pk.CurrentFriendship.ToString(), numeric: true);
        page.AddView(friendship, Fill());

        string? Apply()
        {
            if (!TryResolve(src.Species, species.Text, out int sp) || sp <= 0)
                return "Espécie não reconhecida. Escolha um nome da lista.";
            if (!TryResolve(src.Items, item.Text, out int heldItem))
                return "Item não reconhecido. Escolha um nome da lista ou deixe vazio.";

            bool speciesChanged = sp != pk.Species;
            if (speciesChanged)
            {
                pk.Species = (ushort)sp;
                pk.Form = 0;
                pk.SetAbilityIndex(0);
                pk.SetSaneGender(pk.GetSaneGender());
            }
            else
            {
                if (form != null)
                    pk.Form = (byte)Math.Clamp(ParseInt(form.Text, pk.Form), 0, formCount - 1);
                if (ability != null && ability.SelectedItemPosition >= 0)
                    pk.SetAbilityIndex(ability.SelectedItemPosition);
            }

            pk.CurrentLevel = (byte)Math.Clamp(ParseInt(level.Text, pk.CurrentLevel), 1, 100);

            var nick = nickname.Text?.Trim() ?? "";
            if (nick.Length > pk.MaxStringLengthNickname)
                nick = nick[..pk.MaxStringLengthNickname];
            pk.SetNickname(nick); // an empty string restores the species name

            if (shiny.Checked != pk.IsShiny)
                pk.SetIsShiny(shiny.Checked);

            if (nature != null)
            {
                int n = nature.SelectedItemPosition;
                if (n >= 0 && n < natures.Count)
                    pk.SetNature((Nature)natures[n].Value);
            }

            pk.HeldItem = heldItem;
            pk.CurrentFriendship = (byte)Math.Clamp(ParseInt(friendship.Text, pk.CurrentFriendship), 0, 255);
            Recalculate(pk);
            return null;
        }

        // Picking a species from the list updates the preview tile straight away.
        species.ItemClick += (_, _) =>
        {
            var error = Apply();
            if (error != null)
                Toast(error);
            Refresh();
        };
        return Apply;
    }

    private Func<string?> StatsTab(LinearLayout page, PKM pk)
    {
        var ivs = StatRow(page, $"IVs (0 a {pk.MaxIV})",
            [pk.IV_HP, pk.IV_ATK, pk.IV_DEF, pk.IV_SPA, pk.IV_SPD, pk.IV_SPE]);
        var evs = StatRow(page, $"EVs (0 a {pk.MaxEV})",
            [pk.EV_HP, pk.EV_ATK, pk.EV_DEF, pk.EV_SPA, pk.EV_SPD, pk.EV_SPE]);

        try
        {
            var calc = pk.Clone();
            calc.ResetPartyStats();
            page.AddView(Label(
                "Stats calculados\n" +
                $"HP {calc.Stat_HPMax} · Atq {calc.Stat_ATK} · Def {calc.Stat_DEF}\n" +
                $"AtqE {calc.Stat_SPA} · DefE {calc.Stat_SPD} · Vel {calc.Stat_SPE}"), Fill());
        }
        catch (Exception)
        {
            // The calculated stats are informative only.
        }
        page.AddView(Btn("Recalcular", () => Rebuild()), Fill());

        return () =>
        {
            int maxIV = pk.MaxIV, maxEV = pk.MaxEV;
            pk.IV_HP = Math.Clamp(ParseInt(ivs[0].Text, pk.IV_HP), 0, maxIV);
            pk.IV_ATK = Math.Clamp(ParseInt(ivs[1].Text, pk.IV_ATK), 0, maxIV);
            pk.IV_DEF = Math.Clamp(ParseInt(ivs[2].Text, pk.IV_DEF), 0, maxIV);
            pk.IV_SPA = Math.Clamp(ParseInt(ivs[3].Text, pk.IV_SPA), 0, maxIV);
            pk.IV_SPD = Math.Clamp(ParseInt(ivs[4].Text, pk.IV_SPD), 0, maxIV);
            pk.IV_SPE = Math.Clamp(ParseInt(ivs[5].Text, pk.IV_SPE), 0, maxIV);
            pk.EV_HP = Math.Clamp(ParseInt(evs[0].Text, pk.EV_HP), 0, maxEV);
            pk.EV_ATK = Math.Clamp(ParseInt(evs[1].Text, pk.EV_ATK), 0, maxEV);
            pk.EV_DEF = Math.Clamp(ParseInt(evs[2].Text, pk.EV_DEF), 0, maxEV);
            pk.EV_SPA = Math.Clamp(ParseInt(evs[3].Text, pk.EV_SPA), 0, maxEV);
            pk.EV_SPD = Math.Clamp(ParseInt(evs[4].Text, pk.EV_SPD), 0, maxEV);
            pk.EV_SPE = Math.Clamp(ParseInt(evs[5].Text, pk.EV_SPE), 0, maxEV);
            Recalculate(pk);
            return null;
        };
    }

    private Func<string?> MovesTab(LinearLayout page, PKM pk)
    {
        var src = GameInfo.FilteredSources;
        page.AddView(Label("Golpes (vazio = nenhum)"), Fill());
        ushort[] currentMoves = [pk.Move1, pk.Move2, pk.Move3, pk.Move4];
        var moves = new AutoCompleteTextView[4];
        for (int i = 0; i < 4; i++)
        {
            moves[i] = Picker(src.Moves, currentMoves[i]);
            if (currentMoves[i] == 0)
                moves[i].Text = "";
            page.AddView(moves[i], Fill());
        }
        page.AddView(Label("Os PP são restaurados ao salvar.", 12f), Fill());

        return () =>
        {
            var newMoves = new ushort[4];
            for (int i = 0; i < 4; i++)
            {
                if (!TryResolve(src.Moves, moves[i].Text, out int move))
                    return $"Golpe {i + 1} não reconhecido. Escolha um nome da lista ou deixe vazio.";
                newMoves[i] = (ushort)move;
            }
            pk.SetMoves(newMoves);
            pk.FixMoves();
            Recalculate(pk);
            return null;
        };
    }

    private Func<string?> OriginTab(LinearLayout page, PKM pk)
    {
        page.AddView(Label("Nome do treinador original"), Fill());
        var ot = Field(pk.OriginalTrainerName);
        page.AddView(ot, Fill());
        page.AddView(Label(
            $"ID do treinador: {pk.DisplayTID}\n" +
            $"Jogo de origem: {GameInfo.GetVersionName(pk.Version)}\n" +
            $"Formato dos dados: geração {pk.Format}"), Fill());

        return () =>
        {
            var name = ot.Text?.Trim() ?? "";
            if (name.Length == 0)
                return "O nome do treinador original não pode ficar vazio.";
            if (name.Length > pk.MaxStringLengthTrainer)
                name = name[..pk.MaxStringLengthTrainer];
            pk.OriginalTrainerName = name;
            Recalculate(pk);
            return null;
        };
    }

    // ------------------------------------------------------------------ trainer dialog
    private void TrainerDialog(SaveFile s)
    {
        var col = Column();
        col.AddView(Label($"Nome (até {s.MaxStringLengthTrainer} letras)"), Fill());
        var name = Field(s.OT);
        col.AddView(name, Fill());
        col.AddView(Label($"Dinheiro (até {s.MaxMoney})"), Fill());
        var money = Field(s.Money.ToString(), numeric: true);
        col.AddView(money, Fill());

        new AlertDialog.Builder(this)
            .SetTitle("Treinador")!
            .SetView(col)!
            .SetPositiveButton("Salvar", (_, _) =>
            {
                try
                {
                    var text = name.Text?.Trim() ?? "";
                    if (text.Length == 0)
                    {
                        Toast("O nome não pode ficar vazio.");
                        return;
                    }
                    if (text.Length > s.MaxStringLengthTrainer)
                        text = text[..s.MaxStringLengthTrainer];
                    s.OT = text;
                    long value = long.TryParse(money.Text?.Trim(), out var m) ? m : s.Money;
                    s.Money = (uint)Math.Clamp(value, 0, s.MaxMoney);
                    s.State.Edited = true;
                    Rebuild();
                }
                catch (Exception ex)
                {
                    Message("Erro", ex.Message);
                }
            })!
            .SetNegativeButton("Cancelar", (_, _) => { })!
            .Show();
    }

    // ------------------------------------------------------------------ opening and exporting files
    private void PickSave()
    {
        var intent = new Intent(Intent.ActionOpenDocument);
        intent.AddCategory(Intent.CategoryOpenable);
        intent.SetType("*/*");
        StartActivityForResult(intent, RequestOpen);
    }

    private void ExportSave()
    {
        var s = sav;
        if (s is null)
            return;
        var pending = applyTab?.Invoke();
        if (pending != null)
        {
            Toast(pending);
            return;
        }
        if (!s.State.Exportable)
        {
            Toast("Este tipo de save não pode ser exportado.");
            return;
        }
        var intent = new Intent(Intent.ActionCreateDocument);
        intent.AddCategory(Intent.CategoryOpenable);
        intent.SetType("application/octet-stream");
        intent.PutExtra(Intent.ExtraTitle, savName.Length > 0 ? savName : "save.sav");
        StartActivityForResult(intent, RequestExport);
    }

    private string DisplayName(Android.Net.Uri uri)
    {
        try
        {
            using var cursor = ContentResolver!.Query(uri, ["_display_name"], null, null, null);
            if (cursor != null && cursor.MoveToFirst())
            {
                var name = cursor.GetString(0);
                if (!string.IsNullOrEmpty(name))
                    return name;
            }
        }
        catch (Exception)
        {
            // Some file providers do not report a name; fall through.
        }
        return uri.LastPathSegment ?? "save.sav";
    }

    protected override void OnActivityResult(int requestCode, [GeneratedEnum] Result resultCode, Intent? data)
    {
        base.OnActivityResult(requestCode, resultCode, data);
        var uri = data?.Data;
        if (resultCode != Result.Ok || uri is null)
            return;

        try
        {
            if (requestCode == RequestOpen)
                LoadSave(uri);
            else if (requestCode == RequestExport)
                WriteSave(uri);
        }
        catch (Exception ex)
        {
            Message("Erro", ex.Message);
        }
    }

    private void LoadSave(Android.Net.Uri uri)
    {
        byte[] bytes;
        using (var input = ContentResolver!.OpenInputStream(uri))
        {
            if (input is null)
            {
                Toast("Não foi possível ler o arquivo.");
                return;
            }
            using var buffer = new MemoryStream();
            input.CopyTo(buffer);
            bytes = buffer.ToArray();
        }

        var name = DisplayName(uri);
        if (!SaveUtil.TryGetSaveFile(bytes, out var loaded, name))
        {
            Message("Arquivo não reconhecido",
                "O PKHeX não reconheceu este arquivo como um save de Pokémon. " +
                "Confira se é o arquivo de save (e não a ROM ou um savestate do emulador).");
            return;
        }

        sav = loaded;
        savName = name;
        editing = null;
        currentBox = 0;
        currentTab = 0;
        // Point the game data lists and the legality checker at this save.
        ParseSettings.InitFromSaveFileData(loaded);
        GameInfo.FilteredSources = new FilteredGameDataSource(loaded, GameInfo.Sources);
        GoHome();
    }

    private void WriteSave(Android.Net.Uri uri)
    {
        var s = sav;
        if (s is null)
            return;
        var bytes = s.Write().ToArray();
        using (var output = ContentResolver!.OpenOutputStream(uri))
        {
            if (output is null)
            {
                Toast("Não foi possível gravar o arquivo.");
                return;
            }
            output.Write(bytes, 0, bytes.Length);
        }
        s.State.Edited = false;
        Toast("Save exportado.");
        Rebuild(applyFirst: false);
    }
}
