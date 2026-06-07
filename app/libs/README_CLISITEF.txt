SDK CliSiTef (Software Express) — GPOS720

Origem homologada:
  G:\programas\MOBILE\GPOS\SITEF\libs\clisitef\clisitef\hml

Bibliotecas:
  - app/libs/clisitef-android.jar
  - app/src/main/jniLibs/armeabi-v7a/*.so

Configuração Android (assets):
  - CLSIT          ← conteúdo do clsit.ini (SEM extensão no assets)
  - cheques.ini
  - Cheque.ini     ← alias exigido pela lib nativa

Edite os arquivos em app/tef-config/ e rode:
  gradlew.bat prepareTefAssets assembleDebug

Documentação:
  ...\hml\doc\br\com\softwareexpress\sitef\android\CliSiTef.html
