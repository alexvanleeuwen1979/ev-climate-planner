# Kia Climate Control

Een onafhankelijke Android-app om de klimaatregeling van een Europese Kia e-Niro te plannen. Dit is een communityproject zonder affiliatie met Kia.

- Handmatig starten en stoppen, eenmalig plannen en een weekplanning met meerdere tijdvakken.
- Klimaatprofielen, temperatuur, voorruitontdooiing en de gecombineerde verwarmingsoptie voor stuur, achterruit en spiegels waar de auto die ondersteunt.
- Cyclusteller op basis van de startbevestiging van het voertuig, met een tijdlijn en meldingshistorie.
- Dashboard met tractieaccu, buitentemperatuur en geschat klimaatverbruik.

De app gebruikt de onofficiële [Hyundai/Kia Connect Python-bibliotheek](https://github.com/Hyundai-Kia-Connect/hyundai_kia_connect_api). Compatibiliteit hangt af van model, regio en de Kia-dienst. Aanvraaglimieten kunnen tijdelijk statusopvragen of klimaatopdrachten verhinderen. Gebruik uitsluitend je eigen voertuig en account. Deel geen Kia-inloggegevens, VIN, locatie of onbewerkte logs in issues.

## Zelf bouwen

De huidige build is ingericht voor Windows. Installeer JDK 21, Python 3.15, Android SDK 36 en Android NDK `28.2.13676358`. Zet `ANDROID_HOME` op de SDK-map en zorg dat `python` op je pad staat. Android 8.0 of nieuwer is vereist op het toestel. De eerste build downloadt Gradle- en Python-afhankelijkheden.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

De debug-APK staat na de build in `app/build/outputs/apk/debug/`. Deze repository bevat de broncode. De toepasselijke licenties van externe afhankelijkheden komen van hun respectieve projecten. Alle gebruikte Kia-beeldmerken behoren toe aan hun rechthebbenden.
