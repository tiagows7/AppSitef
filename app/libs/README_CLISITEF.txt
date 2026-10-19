SDK CliSiTef + Gertec — flavors GPOS720 / GPOS700

## Estrutura

```
app/libs/
  gpos720-debug/   → clisitef-android.jar só do assembleGpos720Debug
  gpos720-release/ → clisitef-android.jar só do assembleGpos720Release
  gpos720/         → PPCompGPOS720 + libgedi GPOS720 (debug e release)
  gpos700/         → clisitef-android.jar do GPOS700 + ppcomp legado + payment
  unused/          → libs de outros modelos (não entram no build)

app/src/main/jniLibs/armeabi-v7a/            → .so CliSiTef compartilhados (crypto/ssl/tls)
app/src/gpos720Debug/jniLibs/armeabi-v7a/   → libclisitef.so do APK debug
app/src/gpos720Release/jniLibs/armeabi-v7a/ → libclisitef.so do APK release
app/src/gpos720/jniLibs/armeabi-v7a/        → libPPCompGPOS720 + libgedi
app/src/gpos700/jniLibs/armeabi-v7a/  → libclisitef mSiTef + libppcomp/libemvcl do Teste
                                         GPOS700 (sem PURE_Open) + libged1 (GEDI pinpad) +
                                         libgedi (impressão). ppcomp faz dlopen("libged1.so").
```

## CliSiTef

Não misturar os pacotes. O jar/so de produção GPOS720 chama o pinpad Setis
(`InterfaceUsuarioPinpad`). No GPOS700 isso derruba o processo em PPConecta
logo depois de "Conectando servidor SiTef".

GPOS720 debug (homologação): `clisitef-7.0.117.108.r2-Simulado-gpos720`
(`7.0.117.108S.r2`, distribuição Simulado)

- `app/libs/gpos720-debug/clisitef-android.jar`
- `app/src/gpos720Debug/jniLibs/armeabi-v7a/libclisitef.so`

GPOS720 release (produção, 31/03/2026): `clisitef-7.0.117.114.r2-Producao-gpos720`
(`7.0.117.114P.r2`, distribuição Producao)

- `app/libs/gpos720-release/clisitef-android.jar`
- `app/src/gpos720Release/jniLibs/armeabi-v7a/libclisitef.so`

GPOS700: `clisitef-7.0.117.108.r2-Simulado-gpos700` (não há pacote Producao-gpos700
na pasta). Esse build usa `PPCompLib` + `br.com.gertec.ppcomp`.

- `app/libs/gpos700/clisitef-android.jar`
- `app/src/gpos700/jniLibs/armeabi-v7a/libclisitef.so`
- CliSiTef GPOS700: jar+so do **mSiTef** do terminal (`clisitef-msitef-android.jar`
  + `libclisitef.so` 2.1 MB). Pinpad nativo: `libppcomp.so` + `libemvcl.so` do
  **Teste GPOS700** (não o mSiTef/Cielo com PURE_Open — esse causa erro 40 /
  abort no GetPIN). GEDI do pinpad em `libged1.so`; impressão em `libgedi.so`.
  Jar PPComp com `mContext` público. `GEDI.init` antes da venda no flavor gpos700.
  Patch no jar (`PPFragment`):
  - `showPinpad` reabre o DialogFragment quando o modo das teclas muda
    (display → numérico). Sem isso o GetPIN caía erro 40 porque
    `showPinpadNumericOnly` era no-op com o pinpad de cartão já aberto.
  - `hidePinpad` engole NPE de `dismiss()` se o FragmentManager já for null
    (crash após aproximação + À Vista no contactless).
  Backup: `clisitef-msitef-android.jar.bak-pre-getpin-show`.
  Fica só no source set / libs gpos700.

`.so` compartilhados (`libcrypto`, `libssl`, `libgsurf_rsa`, `libtlswnb`) ficam
em `app/src/main/jniLibs` — o hash é o mesmo nos dois pacotes.

BC Gertec do pacote 720: PPCompGPOS720-v1.29 (referência de build SE).
No app permanece PPCompGPOS720-v1.33 (mais recente / compatível por LEIAME).

## Builds

```powershell
# Terminal atual (GPOS720) — padrão
.\gradlew.bat :app:assembleGpos720Debug

# SmartPOS GPOS700
.\gradlew.bat :app:assembleGpos700Debug
```

APKs:
- `app/build/outputs/apk/gpos720/debug/siscom-pos-gpos720-debug.apk`
- `app/build/outputs/apk/gpos700/debug/siscom-pos-gpos700-debug.apk`

## Origem das libs

- CliSiTef GPOS720: pacote SE `SDK - CliSiTef Android - GPOS720\clisitef`
- GPOS720 Gertec: PPComp + libgedi em `app/libs/gpos720` e `app/src/gpos720/jniLibs`
- GPOS700: exemplo Gertec `CliSitef_GPOS700_Cielo\sdk` + `jni`
- CliSiTef assets: edite `app/tef-config/` e rode o build (task `prepareTefAssets`)
