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

namespace PKHeXAndroid;

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

    private readonly Stack<Func<View>> history = new();
    private Func<View>? current;
    private int currentBox;

    // ------------------------------------------------------------------ lifecycle / navigation
    protected override void OnCreate(Bundle? savedInstanceState)
    {
        base.OnCreate(savedInstanceState);
        Navigate(Home);
    }

    private void Navigate(Func<View> screen)
    {
        if (current != null)
            history.Push(current);
        current = screen;
        SetContentView(screen());
    }

    /// <summary>Rebuilds the screen that is showing, after its data changed.</summary>
    private void Refresh()
    {
        if (current != null)
            SetContentView(current());
    }

    private void GoBack()
    {
        if (history.Count == 0)
        {
            Finish();
            return;
        }
        current = history.Pop();
        SetContentView(current());
    }

    private void GoHome()
    {
        history.Clear();
        current = Home;
        SetContentView(Home());
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

        var info =
            $"Arquivo: {savName}\n" +
            $"Jogo: {GameInfo.GetVersionName(s.Version)} (geração {s.Generation})\n" +
            $"Treinador: {s.OT}\n" +
            $"ID: {s.DisplayTID}\n" +
            $"Dinheiro: {s.Money}\n" +
            $"Tempo de jogo: {s.PlayedHours}h {s.PlayedMinutes:00}min\n" +
            $"Caixas: {s.BoxCount} · Equipe: {(s.HasParty ? s.PartyCount : 0)}\n" +
            $"Verificação do arquivo: {(s.ChecksumsValid ? "ok" : "com erros")}" +
            (s.State.Edited ? "\n\nHá alterações ainda não exportadas." : "");
        col.AddView(Label(info), Fill());

        if (s.HasBox && s.BoxCount > 0)
            col.AddView(Btn("Caixas", () => Navigate(Boxes)), Fill());
        if (s.HasParty)
            col.AddView(Btn("Equipe", () => Navigate(Party)), Fill());
        col.AddView(Btn("Treinador", () => Navigate(Trainer)), Fill());
        col.AddView(Btn("Exportar save editado", ExportSave), Fill());
        col.AddView(Btn("Abrir outro arquivo", PickSave), Fill());
        return Scrollable(col);
    }

    // ------------------------------------------------------------------ lists of Pokémon
    private static string Describe(PKM pk)
    {
        if (pk.Species == 0)
            return "— vazio —";

        var names = GameInfo.Strings.Species;
        var name = pk.Species < names.Count ? names[pk.Species] : $"#{pk.Species}";
        var text = pk.IsEgg ? $"{name} (ovo)" : $"{name}  Nv. {pk.CurrentLevel}";
        if (pk.IsNicknamed && !pk.IsEgg)
            text += $"  “{pk.Nickname}”";
        if (pk.IsShiny)
            text += "  ★";
        try
        {
            if (!new LegalityAnalysis(pk).Valid)
                text += "  ⚠";
        }
        catch (Exception)
        {
            text += "  ?";
        }
        return text;
    }

    private View SlotList(string title, View? header, int count, Func<int, PKM> get, Action<int, PKM> set, bool party)
    {
        var col = Column();
        col.AddView(Label(title, 20f), Fill());
        if (header != null)
            col.AddView(header, Fill());
        col.AddView(Label("★ brilhante · ⚠ não passou na checagem de legalidade", 12f), Fill());

        var items = new List<string>(count);
        for (int i = 0; i < count; i++)
            items.Add($"{i + 1}. {Describe(get(i))}");

        var list = new ListView(this)
        {
            Adapter = new ArrayAdapter<string>(this, global::Android.Resource.Layout.SimpleListItem1, items),
        };
        list.ItemClick += (_, e) =>
        {
            int index = e.Position;
            var pk = get(index);
            if (pk.Species == 0)
            {
                Toast("Este espaço está vazio.");
                return;
            }
            var copy = pk.Clone();
            Navigate(() => Editor(copy, edited => set(index, edited), party));
        };
        col.AddView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MatchParent, 0, 1f));
        return col;
    }

    private View Boxes()
    {
        var s = sav!;
        int count = s.BoxCount;
        currentBox = Math.Clamp(currentBox, 0, count - 1);
        int box = currentBox;

        var name = s is IBoxDetailNameRead named ? named.GetBoxName(box) : BoxDetailNameExtensions.GetDefaultBoxName(box);
        var header = Row();
        header.AddView(Btn("◀", () => { currentBox = (box + count - 1) % count; Refresh(); }));
        var title = Label($"{name}  ({box + 1}/{count})", 17f);
        title.Gravity = GravityFlags.Center;
        header.AddView(title, Weighted());
        header.AddView(Btn("▶", () => { currentBox = (box + 1) % count; Refresh(); }));

        return SlotList("Caixas", header, s.BoxSlotCount,
            slot => s.GetBoxSlotAtIndex(box, slot),
            (slot, pk) => s.SetBoxSlotAtIndex(pk, box, slot),
            party: false);
    }

    private View Party()
    {
        var s = sav!;
        return SlotList("Equipe", null, s.PartyCount,
            s.GetPartySlotAtIndex,
            (slot, pk) => s.SetPartySlotAtIndex(pk, slot),
            party: true);
    }

    // ------------------------------------------------------------------ Pokémon editor
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

    private View Editor(PKM pk, Action<PKM> store, bool party)
    {
        var src = GameInfo.FilteredSources;
        var col = Column();
        col.AddView(Label("Editar Pokémon", 20f), Fill());

        col.AddView(Label("Espécie"), Fill());
        var species = Picker(src.Species, pk.Species);
        col.AddView(species, Fill());

        col.AddView(Label("Apelido (vazio = nome da espécie)"), Fill());
        var nickname = Field(pk.IsNicknamed ? pk.Nickname : "");
        col.AddView(nickname, Fill());

        col.AddView(Label("Nível"), Fill());
        var level = Field(pk.CurrentLevel.ToString(), numeric: true);
        col.AddView(level, Fill());

        var shiny = new CheckBox(this) { Text = "Brilhante (shiny)", Checked = pk.IsShiny };
        col.AddView(shiny, Fill());

        // Natures exist from generation 3 onwards.
        Spinner? nature = null;
        var natures = src.Natures;
        if (pk.Format >= 3 && natures.Count > 0)
        {
            col.AddView(Label("Natureza"), Fill());
            var texts = new List<string>(natures.Count);
            int selected = 0;
            for (int i = 0; i < natures.Count; i++)
            {
                texts.Add(natures[i].Text);
                if (natures[i].Value == (int)pk.Nature)
                    selected = i;
            }
            var adapter = new ArrayAdapter<string>(this, global::Android.Resource.Layout.SimpleSpinnerItem, texts);
            adapter.SetDropDownViewResource(global::Android.Resource.Layout.SimpleSpinnerDropDownItem);
            nature = new Spinner(this) { Adapter = adapter };
            nature.SetSelection(selected);
            col.AddView(nature, Fill());
        }

        col.AddView(Label("Item segurado (vazio = nenhum)"), Fill());
        var item = Picker(src.Items, pk.HeldItem);
        col.AddView(item, Fill());

        col.AddView(Label("Golpes (vazio = nenhum)"), Fill());
        ReadOnlySpan<ushort> currentMoves = [pk.Move1, pk.Move2, pk.Move3, pk.Move4];
        var moves = new AutoCompleteTextView[4];
        for (int i = 0; i < 4; i++)
        {
            moves[i] = Picker(src.Moves, currentMoves[i]);
            if (currentMoves[i] == 0)
                moves[i].Text = "";
            col.AddView(moves[i], Fill());
        }

        var ivs = StatRow(col, $"IVs (0 a {pk.MaxIV})",
            [pk.IV_HP, pk.IV_ATK, pk.IV_DEF, pk.IV_SPA, pk.IV_SPD, pk.IV_SPE]);
        var evs = StatRow(col, $"EVs (0 a {pk.MaxEV})",
            [pk.EV_HP, pk.EV_ATK, pk.EV_DEF, pk.EV_SPA, pk.EV_SPD, pk.EV_SPE]);

        col.AddView(Label("Amizade (0 a 255)"), Fill());
        var friendship = Field(pk.CurrentFriendship.ToString(), numeric: true);
        col.AddView(friendship, Fill());

        // Copies what is on screen into the Pokémon. Returns an error message, or null when it worked.
        string? Apply()
        {
            if (!TryResolve(src.Species, species.Text, out int sp) || sp <= 0)
                return "Espécie não reconhecida. Escolha um nome da lista.";
            if (!TryResolve(src.Items, item.Text, out int heldItem))
                return "Item não reconhecido. Escolha um nome da lista ou deixe vazio.";
            Span<ushort> newMoves = stackalloc ushort[4];
            for (int i = 0; i < 4; i++)
            {
                if (!TryResolve(src.Moves, moves[i].Text, out int move))
                    return $"Golpe {i + 1} não reconhecido. Escolha um nome da lista ou deixe vazio.";
                newMoves[i] = (ushort)move;
            }

            if (sp != pk.Species)
            {
                pk.Species = (ushort)sp;
                pk.Form = 0;
                pk.SetAbilityIndex(0);
                pk.SetSaneGender(pk.GetSaneGender());
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
            pk.SetMoves(newMoves);
            pk.FixMoves();

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

            pk.CurrentFriendship = (byte)Math.Clamp(ParseInt(friendship.Text, pk.CurrentFriendship), 0, 255);

            if (party || pk.PartyStatsPresent)
                pk.ResetPartyStats();
            pk.RefreshChecksum();
            return null;
        }

        col.AddView(Btn("Checar legalidade", () =>
        {
            try
            {
                var error = Apply();
                if (error != null)
                {
                    Toast(error);
                    return;
                }
                var analysis = new LegalityAnalysis(pk);
                Message(analysis.Valid ? "Legal" : "Não é legal", analysis.Report());
            }
            catch (Exception ex)
            {
                Message("Erro", ex.Message);
            }
        }), Fill());

        col.AddView(Btn("Salvar no espaço", () =>
        {
            try
            {
                var error = Apply();
                if (error != null)
                {
                    Toast(error);
                    return;
                }
                store(pk);
                sav!.State.Edited = true;
                Toast("Salvo. Lembre de exportar o save na tela inicial.");
                GoBack();
            }
            catch (Exception ex)
            {
                Message("Erro", ex.Message);
            }
        }), Fill());

        col.AddView(Btn("Cancelar", GoBack), Fill());
        return Scrollable(col);
    }

    // ------------------------------------------------------------------ trainer
    private View Trainer()
    {
        var s = sav!;
        var col = Column();
        col.AddView(Label("Treinador", 20f), Fill());

        col.AddView(Label($"Nome (até {s.MaxStringLengthTrainer} letras)"), Fill());
        var name = Field(s.OT);
        col.AddView(name, Fill());

        col.AddView(Label($"Dinheiro (até {s.MaxMoney})"), Fill());
        var money = Field(s.Money.ToString(), numeric: true);
        col.AddView(money, Fill());

        col.AddView(Btn("Salvar", () =>
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
                Toast("Salvo. Lembre de exportar o save na tela inicial.");
                GoBack();
            }
            catch (Exception ex)
            {
                Message("Erro", ex.Message);
            }
        }), Fill());
        col.AddView(Btn("Cancelar", GoBack), Fill());
        return Scrollable(col);
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
        currentBox = 0;
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
        Refresh();
    }
}
