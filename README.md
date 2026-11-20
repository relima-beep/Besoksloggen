# Besöksloggen för Android

Besöksloggen sparar besök som varar längre än den minimitid du väljer. Standard är 20 minuter. Fliken **20:e–20:e** visar besök, ankomst, sluttid och summerad tid för jobb, hemma, krogar och restauranger. Perioden går i svensk tid från klockan 00.00 den 20:e till samma tid nästa 20:e. Fliken **Dag** visar dagens datum, besök, sammanlagd registrerad tid och tid per kategori. Bläddra mellan dagar med pilarna. Välj **Dela dagsrapport** för att skicka eller spara dagens sammanställning via telefonens delningsmeny. Besök som korsar periodgränsen delas när totalsummorna beräknas.

## Arbetstid

På fliken **20:e–20:e** kan du lägga in dagens ankomst och senaste avresetid. Resor under arbetsdagen ändrar inte dagens start- och sluttid. Tiden rundas upp till närmaste halvtimme, vilket lägger till högst 29 minuter per dag. Tryck på en sparad dag för att rätta tiderna. Perioden och arbetstidssumman följer den 20:e till den 20:e.

Under **Jobba hemifrån** finns en separat arbetsklocka. Starta den när du börjar och stoppa när du är klar. Den fortsätter att räknas även om appen stängs och sparas när du stoppar den.

## Kom igång

1. Installera APK-filen på en telefon med Android 8 eller senare.
2. Välj **Platser** och ställ in **Jobb** och **Hemma** med adressökning eller **Använd min position**. Standardradien är 150 meter och kan ändras för varje adress.
3. Tryck **Starta** på fliken **20:e–20:e**. Ge appen exakt platsbehörighet. Tillåt aviseringar om du vill ha en genväg till att öppna eller pausa appen.
4. Öppna ett besök för att skriva en **Anteckning**, döpa platsen eller välja exempelvis **Krog**. Anteckningar sparas för varje enskilt besök. Plats och kategori kan sparas till framtida besök.
5. Öppna **Inställningar** för att ändra minimitiden och lägga till **Adressvarningar**. Ange en adress eller använd din position. Varje adress har en egen radie och tidsgräns. Varningen kan skickas en gång per vistelse och utlöses vid nästa tillräckligt noggranna positionsmätning efter gränsen. Tillåt aviseringar och slå på ljud för **Adressvarningar** i Android för att höra dem, enligt telefonens ljudinställningar.

Androids platstjänst måste vara på. För automatisk start efter en omstart, välj **Starta automatiskt efter omstart** på fliken **Platser** och följ steget för platsbehörighet. Tvångsstopp i Android kräver att du öppnar appen igen för att fortsätta. Längre avbrott i positionsmätningen räknas inte som besökstid.

Besök och sparade adresser ligger i appens lokala databas. Adressuppslag använder telefonens geokodningstjänst när den finns tillgänglig. Adresser och utvalda platsnamn kan hämtas från den tjänsten. Du kan öppna en position i en kartapp från besökets detaljvy.

## Källkod

Öppna mappen i Android Studio med Android SDK 35, Gradle 8.11.1 och JDK 17. Bygg en debug-APK med projektets uppgift `assembleDebug`. Appen använder Androids egna API:er och behöver ingen API-nyckel.

Filen `app/src/test/java/se/besoksloggen/VisitLogicChecks.java` kan köras som ett Java-program tillsammans med `VisitLogic.java` för att kontrollera gränsvärden, dubbla positioner, uteblivna mätningar, periodbyten och adressvarningar.

En uppdatering som ska installeras över en befintlig APK måste signeras med samma nyckel. Säkerhetskopiera din egen signeringsnyckel när du börjar distribuera egna byggen.
