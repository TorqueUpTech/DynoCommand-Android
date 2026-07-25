# Skill: Add a live read-out field to the left panel

The left panel (Live read-outs) is a 2-column `Grid` inside `MainWindow.xaml` (~line 87).
Data flows: `DynoState`/`CommandState` → `MainViewModel.Refresh()` → bound property → XAML.

## Steps

### 1. Add the field source (if new data)

If the value comes from the EDM, add it to `Protocol/DynoState.cs`:
```csharp
public double MyField;
```
If it's derived from command state, it belongs in `Protocol/CommandState.cs` or computed inline in `Refresh()`.

### 2. Expose a string property on MainViewModel

In `ViewModel/MainViewModel.cs`, add a backing property near the other read-out properties (~line 349):
```csharp
public string MyLabel { get; private set; } = "—";
```

### 3. Populate it in `Refresh()`

Inside `Refresh()` (~line 384), assign the formatted value:
```csharp
MyLabel = _ctrl.State.MyField.ToString("F1");
```

Then add `nameof(MyLabel)` to the `OnPropertyChanged` batch at the bottom of `Refresh()` (~line 428).

### 4. Add a row in MainWindow.xaml

In the `Grid.RowDefinitions` block (~line 92), add one more `<RowDefinition/>`.
Then add two `TextBlock` elements using the next available row index:
```xml
<TextBlock Text="My field" Style="{StaticResource Dim}" Grid.Row="N"/>
<TextBlock Text="{Binding MyLabel}" Grid.Row="N" Grid.Column="1"/>
```
Currently rows are: 0=Roller revs, 1=Raw master, 2=Ramp rate, 3=Brake, 4=Hands-free, 5=Last RX.

## Notes
- `Refresh()` runs every 100 ms on the dispatcher thread — no cross-thread concern.
- All read-out bindings are `Mode=OneWay` by default (read-only); don't make them two-way.
- The `Dim` style sets the label to a muted colour (#9AA4B0); `Grid.Column="1"` for the value.
