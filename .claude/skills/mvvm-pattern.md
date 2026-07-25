# Skill: WPF MVVM wiring pattern

## Architecture

```
MainWindow.xaml  ──DataContext──▶  MainViewModel
                                        │
                                   DynoController
                                   ├── CommandState   (what operator wants → TX)
                                   ├── DynoState      (what EDM says → RX)
                                   ├── FrameParser    (RX framing/CRC)
                                   └── _txTimer       (50ms, ~20 Hz)
```

## Adding a command (button)

1. Add `RelayCommand MyCommand { get; }` property to `MainViewModel`.
2. In the constructor, assign: `MyCommand = new RelayCommand(DoMyThing, () => canExecuteExpression);`
3. In XAML: `<Button Command="{Binding MyCommand}" Content="..."/>`
4. If the can-execute condition changes, call `MyCommand.RaiseCanExecuteChanged()`.

## Adding a two-way bound property

```csharp
public double MyValue
{
    get => _ctrl.Command.MyField;
    set { _ctrl.Command.MyField = Math.Max(0, value); OnPropertyChanged(); }
}
```
XAML: `<TextBox Text="{Binding MyValue, UpdateSourceTrigger=PropertyChanged}"/>`

## UI refresh pattern

`_ui` is a `DispatcherTimer` at 100 ms. All live read-outs are updated in `Refresh()`:
1. Compute the formatted string value.
2. Assign to the backing property (e.g. `ActualSpeed = "..."`)
3. Call `OnPropertyChanged(nameof(ActualSpeed))` in the batch at the bottom of `Refresh()`.

Never call `OnPropertyChanged` inside the setter of a read-only display property — they have no setter. Only `OnPropertyChanged` in `Refresh()`.

## Visibility binding

Use `BooleanToVisibilityConverter` (key `B2V`):
```xml
<StackPanel Visibility="{Binding ShowMyPanel, Converter={StaticResource B2V}}">
```
Expose `bool ShowMyPanel` and call `OnPropertyChanged(nameof(ShowMyPanel))` when the condition changes (do it inside `RaiseVisibility()`).

## ModeBrushConverter

Colours mode buttons green when active:
```xml
Background="{Binding ModeText, Converter={StaticResource ModeBrush}, ConverterParameter=Speed}"
```
The converter returns green when the bound value equals `ConverterParameter`, else the default dark button colour.
For ramp-run buttons, the tag string `"ACTIVE"` is used with `ConverterParameter=ACTIVE`.

## Settings

`AppSettings` is loaded by `SettingsStore.Load()` at startup and saved by `SettingsStore.Save()` after the settings dialog. `ApplySettings()` pushes relevant fields into `_ctrl.Command`. Hardware config (gear ratio, roller diameter, units) lives here — not hardcoded.
