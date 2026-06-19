# Analog to WiFi Speakers 🎵📱

An Android application designed to turn any old tablet into a high-quality Wi-Fi audio bridge. By connecting an external USB sound card (USB DAC) to your tablet, this app captures the analog audio input and streams it losslessly across your home network using the Google Cast framework and a local HLS server. 

Keep your high-end analog audio equipment alive and stream your music anytime, anywhere!


## 🚀 Key Features

- **USB DAC Support:** Seamlessly captures analog audio from external sound cards via USB.
- **Local HLS Streaming:** Runs a background `LocalHttpServer` on port 9090 to host a live audio stream.
- **Google Cast Integration:** Stream your synchronized music directly to Chromecast, Nest speakers, or speaker groups.
- **Matrix Visualizer Panel:** Beautiful interface with a responsive real-time spectrum analyzer for your tablet screen.
- **Sync Optimization:** Smart internal delay buffer (`VISUAL_DELAY_NS`) ensures the on-screen visualizer matrix stays perfectly in sync with the Wi-Fi speaker output.

---

## 🛠️ Requirements & Setup

1. **Hardware:** An Android tablet, an external USB Audio Interface / Sound Card (e.g., Behringer U-Control or compatible USB DAC), and an OTG adapter cable.
2. **Audio Input:** Connect your analog source (turntable, phone, PC) to the input of the sound card.
3. **App Setup:** Install the app, grant the required `RECORD_AUDIO` and `INTERNET` permissions, plug in the USB card, and use the Cast button to connect your network speakers.

---

## 📜 License & Royalties

This project is published under a **Custom Commercial Royalty License** (see the full `LICENSE` file for details):
- **Personal Use:** 100% Free to view, modify, download, and use at home.
- **Commercial Use:** Any distribution for a fee, resale, or commercial application (including publishing this app on the Google Play Store) strictly requires a **15% royalty fee** on all gross revenue payable to the original author (Lorenzo Vandekerckhove).
