# -*- coding: utf-8 -*-
"""
Сравнение показаний метеостанции с данными ближайшей официальной метеостанции из архива rp5.ru.

Использование:
    python compare.py станция.csv rp5.csv [--alt 60]

станция.csv — экспорт из приложения (кнопка «Экспорт журнала в CSV») или файл log.csv со станции
              (http://<адрес станции>/api/log.csv; для него обязателен --alt — высота датчика, м).
rp5.csv     — архив rp5.ru: «Архив погоды» нужного города → вкладка «Скачать архив погоды» →
              формат CSV, кодировка UTF-8, период измерений → «Выбрать в файл».

Результат (в папке рядом со скриптом, подпапка results/):
    comparison.csv      — пары «станция / эталон» для каждого срока наблюдений
    fig6_pressure.png   — рисунок 6 для работы
    fig7_temperature.png— рисунок 7 для работы
    fig8_humidity.png   — дополнительный рисунок
    summary.txt         — таблица 3 и готовый текст для раздела «Результаты»

Нужны библиотеки: pip install matplotlib
"""
import argparse
import csv
import io
import math
import os
from datetime import datetime, timedelta, timezone

MSK = timezone(timedelta(hours=3))
MMHG = 1.333224


def num(s):
    s = (s or "").strip().strip('"').replace(",", ".").replace("−", "-")
    try:
        return float(s)
    except ValueError:
        return None


def read_text(path):
    raw = open(path, "rb").read()
    for enc in ("utf-8-sig", "cp1251"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            pass
    return raw.decode("utf-8", "replace")


def sea_level(p, alt):
    return p / (1.0 - alt / 44330.0) ** 5.255


def load_station(path, alt):
    """Возвращает список (ts, T, RH, P0)."""
    text = read_text(path)
    lines = [l for l in text.splitlines() if l.strip()]
    rows = []
    if lines and lines[0].startswith("Дата и время"):          # экспорт из приложения
        for l in lines[1:]:
            f = l.split(";")
            ts, t, h, p0 = int(f[1]), num(f[2]), num(f[3]), num(f[5])
            rows.append((ts, t, h, p0))
    else:                                                     # log.csv со станции: ts;t;h;p
        if alt is None:
            raise SystemExit("Для log.csv укажите высоту датчика: --alt 60")
        for l in lines:
            f = l.split(";")
            if not f[0].strip().isdigit():
                continue
            h = num(f[2])
            rows.append((int(f[0]), num(f[1]), h if h is not None and h >= 0 else None, sea_level(num(f[3]), alt)))
    rows.sort()
    return rows


def load_rp5(path):
    """Возвращает список (ts, T, RH, P0 гПа) из архива rp5 (время — местное, UTC+3)."""
    text = read_text(path)
    body = [l for l in text.splitlines() if l.strip() and not l.startswith("#")]
    reader = csv.reader(io.StringIO("\n".join(body)), delimiter=";")
    header = [h.strip().strip('"') for h in next(reader)]
    i_time = next(i for i, h in enumerate(header) if h.startswith("Местное время"))
    i_t, i_p, i_u = header.index("T"), header.index("P"), header.index("U")
    rows = []
    for f in reader:
        if len(f) <= max(i_t, i_p, i_u):
            continue
        try:
            dt = datetime.strptime(f[i_time].strip().strip('"'), "%d.%m.%Y %H:%M").replace(tzinfo=MSK)
        except ValueError:
            continue
        p = num(f[i_p])
        rows.append((int(dt.timestamp()), num(f[i_t]), num(f[i_u]), p * MMHG if p is not None else None))
    rows.sort()
    return rows


def match(station, ref, tol=10 * 60):
    """Для каждого срока эталона — ближайшая запись станции (не дальше tol секунд)."""
    pairs = []
    j = 0
    for r in ref:
        while j + 1 < len(station) and abs(station[j + 1][0] - r[0]) <= abs(station[j][0] - r[0]):
            j += 1
        if station and abs(station[j][0] - r[0]) <= tol:
            pairs.append((r[0], station[j], r))
    return pairs


def stats(d):
    d = [x for x in d if x is not None]
    n = len(d)
    if n == 0:
        return None
    mean = sum(d) / n
    mae = sum(abs(x) for x in d) / n
    sigma = math.sqrt(sum((x - mean) ** 2 for x in d) / (n - 1)) if n > 1 else 0.0
    return {"n": n, "mean": mean, "mae": mae, "sigma": sigma, "max": max(abs(x) for x in d)}


def fmt(x, k=2):
    return f"{x:.{k}f}".replace(".", ",")


def plot(station, pairs, idx, title, unit, fname, number):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import matplotlib.dates as mdates
    plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 11,
                         "axes.spines.top": False, "axes.spines.right": False})
    fig, ax = plt.subplots(figsize=(9, 4.2))
    xs = [datetime.fromtimestamp(s[0], MSK) for s in station if s[idx] is not None]
    ys = [s[idx] for s in station if s[idx] is not None]
    ax.plot(xs, ys, color="#0B2545", lw=1.6, label="Самодельная метеостанция")
    rx = [datetime.fromtimestamp(p[0], MSK) for p in pairs if p[2][idx] is not None]
    ry = [p[2][idx] for p in pairs if p[2][idx] is not None]
    ax.plot(rx, ry, "o", color="#E07A2E", ms=6, label="Официальная метеостанция (rp5.ru)")
    ax.set_ylabel(unit)
    ax.xaxis.set_major_formatter(mdates.DateFormatter("%d.%m\n%H:%M", tz=MSK))
    ax.grid(alpha=.3)
    ax.legend(frameon=False)
    fig.savefig(fname, dpi=200, bbox_inches="tight")
    plt.close(fig)
    print(f"Рисунок {number}. {title} — {fname}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("station")
    ap.add_argument("rp5")
    ap.add_argument("--alt", type=float, default=None, help="высота датчика над уровнем моря, м (для log.csv)")
    a = ap.parse_args()

    station = load_station(a.station, a.alt)
    ref = [r for r in load_rp5(a.rp5) if station and station[0][0] - 600 <= r[0] <= station[-1][0] + 600]
    pairs = match(station, ref)
    if not pairs:
        raise SystemExit("Нет совпадающих по времени записей. Проверьте период архива rp5.")

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results")
    os.makedirs(out, exist_ok=True)

    with open(os.path.join(out, "comparison.csv"), "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(["Дата и время (МСК)", "P0 станция, гПа", "P0 эталон, гПа", "ΔP, гПа",
                    "T станция, °C", "T эталон, °C", "ΔT, °C", "φ станция, %", "φ эталон, %", "Δφ, %"])
        for ts, s, r in pairs:
            def d(i):
                return None if s[i] is None or r[i] is None else s[i] - r[i]
            cells = [datetime.fromtimestamp(ts, MSK).strftime("%d.%m.%Y %H:%M")]
            for i, k in ((3, 2), (1, 1), (2, 0)):
                cells += ["" if v is None else fmt(v, k) for v in (s[i], r[i], d(i))]
            w.writerow(cells)

    res = {}
    for name, i in (("P", 3), ("T", 1), ("H", 2)):
        res[name] = stats([None if s[i] is None or r[i] is None else s[i] - r[i] for _, s, r in pairs])

    t0 = datetime.fromtimestamp(station[0][0], MSK).strftime("%d.%m.%Y %H:%M")
    t1 = datetime.fromtimestamp(station[-1][0], MSK).strftime("%d.%m.%Y %H:%M")
    lines = [f"Период: {t0} – {t1}; записей станции: {len(station)}; совпало со сроками эталона: {len(pairs)}", "",
             "Таблица 3. Отклонения показаний метеостанции от данных официальной метеостанции",
             "Величина | Среднее Δ | Среднее |Δ| | σ | Максимальное |Δ| | n"]
    for name, unit, key in (("Давление P0", "гПа", "P"), ("Температура", "°C", "T"), ("Относительная влажность", "%", "H")):
        s = res[key]
        if s:
            lines.append(f"{name}, {unit} | {fmt(s['mean'])} | {fmt(s['mae'])} | {fmt(s['sigma'])} | {fmt(s['max'])} | {s['n']}")
    p = res["P"]
    if p:
        # паспортная погрешность BME280: ±1,0 гПа при 0…65 °C, ±1,7 гПа при −20…0 °C
        dp = [(s[3] - r[3], s[1]) for _, s, r in pairs if s[3] is not None and r[3] is not None and s[1] is not None]
        within = sum(1 for d, t in dp if abs(d) <= (1.0 if t >= 0 else 1.7))
        cold = sum(1 for _, t in dp if t < 0)
        limit = 1.0 if cold == 0 else (1.7 if cold == len(dp) else None)
        if limit is None:
            limit_text = "±1,0 гПа выше 0 °C и ±1,7 гПа ниже 0 °C"
            ok = within == len(dp)
        else:
            limit_text = f"±{fmt(limit, 1)} гПа"
            ok = p["mae"] <= limit
        verdict = "подтвердилась" if ok else "не подтвердилась"
        lines += ["", f"Паспортная погрешность для условий серии: {limit_text}. "
                      f"В пределах паспортной погрешности: {within} из {len(dp)} сроков.",
                  f"Гипотеза по давлению {verdict}: среднее |Δ| = {fmt(p['mae'])} гПа.",
                  f"Систематическая погрешность (среднее Δ) = {fmt(p['mean'])} гПа. "
                  f"Поправка для прибора: {fmt(-p['mean'])} гПа."]
    t = res["T"]
    if t:
        verdict = "подтвердилась" if t["mae"] <= 1.5 else "не подтвердилась"
        lines.append(f"Гипотеза по температуре (среднее |Δ| ≤ 1,5 °C) {verdict}: среднее |Δ| = {fmt(t['mae'])} °C.")
    text = "\n".join(lines)
    open(os.path.join(out, "summary.txt"), "w", encoding="utf-8").write(text + "\n")
    print(text, "\n")

    plot(station, pairs, 3, "Давление по данным метеостанции и эталона", "Давление на уровне моря, гПа",
         os.path.join(out, "fig6_pressure.png"), 6)
    plot(station, pairs, 1, "Температура по данным метеостанции и эталона", "Температура, °C",
         os.path.join(out, "fig7_temperature.png"), 7)
    plot(station, pairs, 2, "Относительная влажность по данным метеостанции и эталона", "Влажность, %",
         os.path.join(out, "fig8_humidity.png"), 8)


if __name__ == "__main__":
    main()
