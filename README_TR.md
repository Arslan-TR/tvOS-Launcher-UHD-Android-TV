# tvOS Launcher UHD: Android TV ve Google TV için Apple TV tarzı ana ekran

> 🇬🇧 English: **[README.md](README.md)** · Orijinal proje: **[README_UPSTREAM_TR.md](README_UPSTREAM_TR.md)**

![Saat ve takvim widget'lı tvOS / Apple TV tarzı Android TV ana ekranı](docs/uhd/hero-home-clock-calendar.jpg)

[techgenius1/tvOS-Launcher](https://github.com/techgenius1/tvOS-Launcher) (v2.1.2, Apache-2.0) projesinin çatalı.
Ucuz bir Android TV veya Google TV kutusunu Apple TV görünümlü bir TV'ye çevirir. Orijinaldeki her şey durur
(sıvı cam dock, gece sahneleri, İzlemeye Devam Et, debloater); üstüne **uygulama kurmadan telefon kumandası**,
**ana ekran widget'ları** (saat, takvim), **saatli video / şömine ekran koruyucu**, **4K sahneler** ve birkaç
iyileştirme eklenmiştir. 1,8 GB RAM'li, MediaTek, Android 14 bir TV'de geliştirildi ve denendi.

Anahtar kelimeler: Android TV launcher, Google TV launcher, tvOS launcher, Apple TV tarzı ana ekran, ekran koruyucu,
şömine ekran koruyucu, telefon kumandası, TV widget, 4K duvar kâğıdı, Mi Box, Chromecast with Google TV.

## Bu çatalın eklediği özellikler

| Özellik | Açıklama |
|---|---|
| **Telefon kumandası (uygulama gerekmez)** | TV küçük bir web sunucusu (port `8765`) çalıştırır. TV'deki QR kodu aynı Wi-Fi'daki telefon kamerasıyla okutun. Yön tuşları, OK, geri/ana ekran, ses, oynat/duraklat, uygulama kısayolları, tarayıcı ve trackpad sekmeleri. |
| **Ana ekran widget'ları** | Ana ekrana yerleştirilen analog saat ve aylık takvim (yukarıdaki görsele bakın). |
| **Ekran koruyucu (DreamService)** | Döngüde, sessiz video (`somine.mp4`); video yoksa kodla çizilen hareketli şömine. Üstünde büyük saat ve tarih. |
| **4K sahneler** | Gece sahneleri 4K yüzeyde çizilir; önceden yükleme ve arka plan yükleyici vardır. |
| **Cam geçişi** | Ana ekran ile kontrol paneli arasında yumuşak cam geçişi. |
| **Home tuşu kurtarma** | Home tuşunu Google TV başlatıcısından geri alır, erişilebilirlik yardımcıları. |
| **YouTube ileri/geri sonrası otomatik oynatma** | Sarma sonrası video kendiliğinden devam eder. |
| **"İzlemeye Devam Et" varsayılan açık** | Orijinal v2.1.2 özelliği, burada hazır açık gelir. |
| **Otomatik güncelleme penceresi kapalı** | Uygulama içi güncelleyici artık kendiliğinden açılmaz; Ayarlar'dan elle denetlenebilir. |

### Telefon kumandası

| TV'deki QR kod | Telefondaki kumanda |
|---|---|
| ![QR](docs/uhd/phone-remote-qr.jpg) | <img src="docs/uhd/phone-remote-app.png" width="260"> |

### Duvar kâğıtları ve widget'lar

![Saat ve takvim widget'lı duvar kâğıtları](docs/uhd/wallpapers-widgets-1.jpg)
![Diğer duvar kâğıtları](docs/uhd/wallpapers-widgets-2.jpg)
![Takvim widget'lı çöl sahnesi](docs/uhd/home-desert-calendar.jpg)

## Ekran koruyucu

Başlatıcıyı sistem ekran koruyucusu yapın (Ayarlar → Cihaz tercihleri → Ekran koruyucu). Sırasıyla şunu kullanır:

1. Uygulamanın harici dosyalar klasöründeki `somine.mp4`: sessiz, döngüde ve ekranı dolduracak şekilde kırpılarak.
2. Dosya yoksa yerleşik kodla çizilen şömine (indirme gerekmez).

Kendi videonuzu TV'ye siz yükleyin. Video bu depoda **yoktur**; Pexels videoları
[Pexels lisansı](https://www.pexels.com/license/) ile ücretsiz kullanılabilir:

```bash
adb push somine.mp4 /sdcard/Android/data/<paket.adiniz>/files/somine.mp4
```

Az RAM'li TV'lerde 1080p H.264 dosya güvenlidir. Eski duvar kâğıdı karışımı ekran koruyucuyu istiyorsanız
`screensaver_style` tercihini (dosya `screensaver`) `scenes` yapın.

## Derleme

JDK 17+, Android SDK build-tools 34.0.0 ve platform android-34. Gradle gerekmez.

```bash
./build.sh                                   # -> build/tvos-home.apk (debug imzalı)
VERSION_CODE=150 VERSION_NAME=2.1.2-uhd8 PKG=com.ornek.tvoslauncher ./build.sh
```

`PKG` uygulama kimliğini değiştirir. Kimlik ve imza anahtarı aynı kaldıkça güncelleme verileri korur; her derlemede
`VERSION_CODE` artmalıdır.

## Lisans

Orijinal gibi Apache-2.0; bkz. [LICENSE](LICENSE) ve [NOTICE](NOTICE). Başlatıcının tüm emeği
[techgenius](https://github.com/techgenius1/tvOS-Launcher) ve `NOTICE` içindeki projelere aittir; bu çatal yalnızca
yukarıdaki tabloyu ekler. Apple ile bağlantısı yoktur; "tvOS" Apple Inc. markasıdır.
