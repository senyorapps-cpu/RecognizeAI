package com.example.recognizeai

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.example.recognizeai.databinding.ActivityPrivacyPolicyBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PrivacyPolicyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrivacyPolicyBinding

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacyPolicyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        val langCode = LocaleHelper.getCurrentLanguageCode()
        loadPolicy(langCode)
    }

    private fun loadPolicy(langCode: String) {
        // Try local cache first for instant display
        val cached = getCachedPolicy(langCode)
        if (cached.isNotEmpty()) {
            binding.tvPolicyContent.text = cached
        }

        // Then fetch from server to update
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url("${SessionManager.BASE_URL}/api/privacy-policy")
                    .get()
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: "{}"
                if (!response.isSuccessful) throw Exception("Server error: ${response.code}")

                val json = JSONObject(body)
                // Cache all languages locally
                val prefs = getSharedPreferences("privacy_policy", MODE_PRIVATE)
                val editor = prefs.edit()
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    editor.putString(key, json.optString(key, ""))
                }
                editor.apply()

                val policy = json.optString(langCode, json.optString("en", ""))
                withContext(Dispatchers.Main) {
                    if (policy.isNotEmpty()) {
                        binding.tvPolicyContent.text = policy
                    }
                }
            } catch (e: Exception) {
                Log.e("PrivacyPolicy", "Failed to fetch from server", e)
                withContext(Dispatchers.Main) {
                    // If no cached content, use built-in fallback
                    if (cached.isEmpty()) {
                        binding.tvPolicyContent.text = getFallbackPolicy(langCode)
                    }
                }
            }
        }
    }

    private fun getCachedPolicy(langCode: String): String {
        val prefs = getSharedPreferences("privacy_policy", MODE_PRIVATE)
        return prefs.getString(langCode, "") ?: ""
    }

    private fun getFallbackPolicy(langCode: String): String {
        return when (langCode) {
            "ru" -> POLICY_RU
            "es" -> POLICY_ES
            "fr" -> POLICY_FR
            "de" -> POLICY_DE
            "pt" -> POLICY_PT
            else -> POLICY_EN
        }
    }

    companion object {
        private const val POLICY_EN = """Privacy Policy

Last updated: February 2026

TripAI ("we", "our", or "us") operates the TripAI mobile application. This Privacy Policy explains how we collect, use, and protect your information when you use our app.

1. Information We Collect

- Photos & Camera: We access your camera to capture photos of landmarks. Photos are uploaded to our servers for AI analysis and stored to provide you with landmark information.
- Location Data: With your permission, we collect your device's location to enhance landmark identification accuracy and show nearby places.
- Device Information: We collect your device identifier to associate your data with your account and enable guest access.
- Account Information: If you sign in with Google, we receive your name, email address, and profile photo from Google.
- Language Preference: We store your selected language preference to provide the app in your chosen language.

2. How We Use Your Information

- To analyze photos and identify landmarks using AI
- To provide historical and cultural information about landmarks
- To save your travel journal and photo history
- To personalize your experience based on language and location
- To improve our AI recognition accuracy

3. Data Storage & Security

Your data is stored on our secure servers. Photos and landmark data are associated with your device or Google account. We implement appropriate security measures to protect your personal information.

4. Data Sharing

We do not sell your personal information. We may share anonymized, aggregated data for analytics purposes. Your photos are processed by our AI systems and are not shared with third parties.

5. Your Rights

You can:
- Delete your account and all associated data by contacting us
- Change your language preference at any time
- Use the app as a guest without providing personal information

6. Children's Privacy

Our app is not directed at children under 13. We do not knowingly collect personal information from children.

7. Changes to This Policy

We may update this policy from time to time. We will notify you of significant changes through the app.

8. Contact Us

If you have questions about this Privacy Policy, please contact us at support@tripai.app"""

        private const val POLICY_RU = """Политика конфиденциальности

Последнее обновление: февраль 2026

TripAI ("мы", "наш" или "нас") управляет мобильным приложением TripAI. Настоящая Политика конфиденциальности объясняет, как мы собираем, используем и защищаем вашу информацию при использовании нашего приложения.

1. Информация, которую мы собираем

- Фотографии и камера: Мы используем вашу камеру для съёмки достопримечательностей. Фотографии загружаются на наши серверы для AI-анализа и хранятся для предоставления информации о достопримечательностях.
- Данные о местоположении: С вашего разрешения мы собираем данные о местоположении вашего устройства для повышения точности определения достопримечательностей и отображения ближайших мест.
- Информация об устройстве: Мы собираем идентификатор вашего устройства для привязки данных к вашему аккаунту и обеспечения гостевого доступа.
- Информация об аккаунте: При входе через Google мы получаем ваше имя, адрес электронной почты и фото профиля от Google.
- Языковые предпочтения: Мы сохраняем выбранный вами язык для предоставления приложения на нужном языке.

2. Как мы используем вашу информацию

- Для анализа фотографий и определения достопримечательностей с помощью AI
- Для предоставления исторической и культурной информации о достопримечательностях
- Для сохранения вашего дневника путешествий и истории фотографий
- Для персонализации опыта на основе языка и местоположения
- Для улучшения точности AI-распознавания

3. Хранение и безопасность данных

Ваши данные хранятся на наших защищённых серверах. Фотографии и данные о достопримечательностях привязаны к вашему устройству или аккаунту Google. Мы применяем соответствующие меры безопасности для защиты вашей личной информации.

4. Передача данных

Мы не продаём вашу личную информацию. Мы можем передавать анонимизированные, агрегированные данные для аналитических целей. Ваши фотографии обрабатываются нашими AI-системами и не передаются третьим лицам.

5. Ваши права

Вы можете:
- Удалить свой аккаунт и все связанные данные, связавшись с нами
- Изменить языковые предпочтения в любое время
- Использовать приложение как гость без предоставления личной информации

6. Конфиденциальность детей

Наше приложение не предназначено для детей младше 13 лет. Мы сознательно не собираем личную информацию детей.

7. Изменения в политике

Мы можем время от времени обновлять эту политику. Мы уведомим вас о существенных изменениях через приложение.

8. Свяжитесь с нами

Если у вас есть вопросы о данной Политике конфиденциальности, свяжитесь с нами по адресу support@tripai.app"""

        private const val POLICY_ES = """Pol\u00EDtica de Privacidad

\u00DAltima actualizaci\u00F3n: febrero 2026

TripAI ("nosotros", "nuestro") opera la aplicaci\u00F3n m\u00F3vil TripAI. Esta Pol\u00EDtica de Privacidad explica c\u00F3mo recopilamos, usamos y protegemos su informaci\u00F3n cuando utiliza nuestra aplicaci\u00F3n.

1. Informaci\u00F3n que recopilamos

- Fotos y c\u00E1mara: Accedemos a su c\u00E1mara para capturar fotos de monumentos. Las fotos se cargan en nuestros servidores para an\u00E1lisis de IA y se almacenan para proporcionarle informaci\u00F3n sobre monumentos.
- Datos de ubicaci\u00F3n: Con su permiso, recopilamos la ubicaci\u00F3n de su dispositivo para mejorar la precisi\u00F3n de identificaci\u00F3n de monumentos y mostrar lugares cercanos.
- Informaci\u00F3n del dispositivo: Recopilamos el identificador de su dispositivo para asociar sus datos con su cuenta y permitir el acceso como invitado.
- Informaci\u00F3n de la cuenta: Si inicia sesi\u00F3n con Google, recibimos su nombre, direcci\u00F3n de correo electr\u00F3nico y foto de perfil de Google.
- Preferencia de idioma: Almacenamos su preferencia de idioma seleccionada para proporcionar la aplicaci\u00F3n en su idioma elegido.

2. C\u00F3mo usamos su informaci\u00F3n

- Para analizar fotos e identificar monumentos usando IA
- Para proporcionar informaci\u00F3n hist\u00F3rica y cultural sobre monumentos
- Para guardar su diario de viaje e historial de fotos
- Para personalizar su experiencia seg\u00FAn el idioma y la ubicaci\u00F3n
- Para mejorar la precisi\u00F3n de nuestro reconocimiento de IA

3. Almacenamiento y seguridad de datos

Sus datos se almacenan en nuestros servidores seguros. Las fotos y los datos de monumentos est\u00E1n asociados con su dispositivo o cuenta de Google. Implementamos medidas de seguridad apropiadas para proteger su informaci\u00F3n personal.

4. Compartir datos

No vendemos su informaci\u00F3n personal. Podemos compartir datos anonimizados y agregados con fines anal\u00EDticos. Sus fotos son procesadas por nuestros sistemas de IA y no se comparten con terceros.

5. Sus derechos

Usted puede:
- Eliminar su cuenta y todos los datos asociados contact\u00E1ndonos
- Cambiar su preferencia de idioma en cualquier momento
- Usar la aplicaci\u00F3n como invitado sin proporcionar informaci\u00F3n personal

6. Privacidad de los ni\u00F1os

Nuestra aplicaci\u00F3n no est\u00E1 dirigida a ni\u00F1os menores de 13 a\u00F1os. No recopilamos conscientemente informaci\u00F3n personal de ni\u00F1os.

7. Cambios en esta pol\u00EDtica

Podemos actualizar esta pol\u00EDtica de vez en cuando. Le notificaremos sobre cambios significativos a trav\u00E9s de la aplicaci\u00F3n.

8. Cont\u00E1ctenos

Si tiene preguntas sobre esta Pol\u00EDtica de Privacidad, cont\u00E1ctenos en support@tripai.app"""

        private const val POLICY_FR = """Politique de Confidentialit\u00E9

Derni\u00E8re mise \u00E0 jour : f\u00E9vrier 2026

TripAI (\u00AB nous \u00BB, \u00AB notre \u00BB) exploite l\u2019application mobile TripAI. Cette Politique de Confidentialit\u00E9 explique comment nous collectons, utilisons et prot\u00E9geons vos informations lorsque vous utilisez notre application.

1. Informations que nous collectons

- Photos et cam\u00E9ra : Nous acc\u00E9dons \u00E0 votre cam\u00E9ra pour capturer des photos de monuments. Les photos sont t\u00E9l\u00E9charg\u00E9es sur nos serveurs pour analyse par IA et stock\u00E9es pour vous fournir des informations sur les monuments.
- Donn\u00E9es de localisation : Avec votre permission, nous collectons la localisation de votre appareil pour am\u00E9liorer la pr\u00E9cision d\u2019identification des monuments et afficher les lieux \u00E0 proximit\u00E9.
- Informations sur l\u2019appareil : Nous collectons l\u2019identifiant de votre appareil pour associer vos donn\u00E9es \u00E0 votre compte et permettre l\u2019acc\u00E8s invit\u00E9.
- Informations du compte : Si vous vous connectez avec Google, nous recevons votre nom, adresse e-mail et photo de profil de Google.
- Pr\u00E9f\u00E9rence linguistique : Nous stockons votre pr\u00E9f\u00E9rence de langue s\u00E9lectionn\u00E9e pour fournir l\u2019application dans la langue de votre choix.

2. Comment nous utilisons vos informations

- Pour analyser les photos et identifier les monuments gr\u00E2ce \u00E0 l\u2019IA
- Pour fournir des informations historiques et culturelles sur les monuments
- Pour sauvegarder votre journal de voyage et votre historique de photos
- Pour personnaliser votre exp\u00E9rience en fonction de la langue et de la localisation
- Pour am\u00E9liorer la pr\u00E9cision de notre reconnaissance par IA

3. Stockage et s\u00E9curit\u00E9 des donn\u00E9es

Vos donn\u00E9es sont stock\u00E9es sur nos serveurs s\u00E9curis\u00E9s. Les photos et les donn\u00E9es des monuments sont associ\u00E9es \u00E0 votre appareil ou compte Google. Nous mettons en \u0153uvre des mesures de s\u00E9curit\u00E9 appropri\u00E9es pour prot\u00E9ger vos informations personnelles.

4. Partage des donn\u00E9es

Nous ne vendons pas vos informations personnelles. Nous pouvons partager des donn\u00E9es anonymis\u00E9es et agr\u00E9g\u00E9es \u00E0 des fins d\u2019analyse. Vos photos sont trait\u00E9es par nos syst\u00E8mes d\u2019IA et ne sont pas partag\u00E9es avec des tiers.

5. Vos droits

Vous pouvez :
- Supprimer votre compte et toutes les donn\u00E9es associ\u00E9es en nous contactant
- Modifier votre pr\u00E9f\u00E9rence linguistique \u00E0 tout moment
- Utiliser l\u2019application en tant qu\u2019invit\u00E9 sans fournir d\u2019informations personnelles

6. Vie priv\u00E9e des enfants

Notre application n\u2019est pas destin\u00E9e aux enfants de moins de 13 ans. Nous ne collectons pas sciemment d\u2019informations personnelles aupr\u00E8s d\u2019enfants.

7. Modifications de cette politique

Nous pouvons mettre \u00E0 jour cette politique de temps en temps. Nous vous informerons des changements importants via l\u2019application.

8. Contactez-nous

Si vous avez des questions sur cette Politique de Confidentialit\u00E9, contactez-nous \u00E0 support@tripai.app"""

        private const val POLICY_DE = """Datenschutzerkl\u00E4rung

Letzte Aktualisierung: Februar 2026

TripAI (\u201Ewir\u201C, \u201Eunser\u201C) betreibt die mobile Anwendung TripAI. Diese Datenschutzerkl\u00E4rung erl\u00E4utert, wie wir Ihre Informationen erfassen, verwenden und sch\u00FCtzen, wenn Sie unsere App nutzen.

1. Informationen, die wir erfassen

- Fotos und Kamera: Wir greifen auf Ihre Kamera zu, um Fotos von Sehensw\u00FCrdigkeiten aufzunehmen. Fotos werden zur KI-Analyse auf unsere Server hochgeladen und gespeichert, um Ihnen Informationen \u00FCber Sehensw\u00FCrdigkeiten zu liefern.
- Standortdaten: Mit Ihrer Erlaubnis erfassen wir den Standort Ihres Ger\u00E4ts, um die Genauigkeit der Erkennung von Sehensw\u00FCrdigkeiten zu verbessern und Orte in der N\u00E4he anzuzeigen.
- Ger\u00E4teinformationen: Wir erfassen die Kennung Ihres Ger\u00E4ts, um Ihre Daten mit Ihrem Konto zu verkn\u00FCpfen und den Gastzugang zu erm\u00F6glichen.
- Kontoinformationen: Wenn Sie sich mit Google anmelden, erhalten wir Ihren Namen, Ihre E-Mail-Adresse und Ihr Profilfoto von Google.
- Sprachpr\u00E4ferenz: Wir speichern Ihre gew\u00E4hlte Sprachpr\u00E4ferenz, um die App in Ihrer gew\u00E4hlten Sprache bereitzustellen.

2. Wie wir Ihre Informationen verwenden

- Zur Analyse von Fotos und Identifizierung von Sehensw\u00FCrdigkeiten mittels KI
- Zur Bereitstellung historischer und kultureller Informationen \u00FCber Sehensw\u00FCrdigkeiten
- Zum Speichern Ihres Reisetagebuchs und Ihrer Fotohistorie
- Zur Personalisierung Ihrer Erfahrung basierend auf Sprache und Standort
- Zur Verbesserung der Genauigkeit unserer KI-Erkennung

3. Datenspeicherung und Sicherheit

Ihre Daten werden auf unseren sicheren Servern gespeichert. Fotos und Daten zu Sehensw\u00FCrdigkeiten sind mit Ihrem Ger\u00E4t oder Google-Konto verkn\u00FCpft. Wir implementieren angemessene Sicherheitsma\u00DFnahmen zum Schutz Ihrer pers\u00F6nlichen Daten.

4. Datenweitergabe

Wir verkaufen Ihre pers\u00F6nlichen Daten nicht. Wir k\u00F6nnen anonymisierte, aggregierte Daten f\u00FCr Analysezwecke weitergeben. Ihre Fotos werden von unseren KI-Systemen verarbeitet und nicht an Dritte weitergegeben.

5. Ihre Rechte

Sie k\u00F6nnen:
- Ihr Konto und alle zugeh\u00F6rigen Daten l\u00F6schen, indem Sie uns kontaktieren
- Ihre Sprachpr\u00E4ferenz jederzeit \u00E4ndern
- Die App als Gast nutzen, ohne pers\u00F6nliche Daten anzugeben

6. Datenschutz f\u00FCr Kinder

Unsere App richtet sich nicht an Kinder unter 13 Jahren. Wir erfassen wissentlich keine pers\u00F6nlichen Daten von Kindern.

7. \u00C4nderungen dieser Richtlinie

Wir k\u00F6nnen diese Richtlinie von Zeit zu Zeit aktualisieren. Wir werden Sie \u00FCber wesentliche \u00C4nderungen \u00FCber die App informieren.

8. Kontaktieren Sie uns

Wenn Sie Fragen zu dieser Datenschutzerkl\u00E4rung haben, kontaktieren Sie uns unter support@tripai.app"""

        private const val POLICY_PT = """Pol\u00EDtica de Privacidade

\u00DAltima atualiza\u00E7\u00E3o: fevereiro 2026

TripAI ("n\u00F3s", "nosso") opera o aplicativo m\u00F3vel TripAI. Esta Pol\u00EDtica de Privacidade explica como coletamos, usamos e protegemos suas informa\u00E7\u00F5es quando voc\u00EA usa nosso aplicativo.

1. Informa\u00E7\u00F5es que coletamos

- Fotos e c\u00E2mera: Acessamos sua c\u00E2mera para capturar fotos de pontos tur\u00EDsticos. As fotos s\u00E3o enviadas para nossos servidores para an\u00E1lise de IA e armazenadas para fornecer informa\u00E7\u00F5es sobre pontos tur\u00EDsticos.
- Dados de localiza\u00E7\u00E3o: Com sua permiss\u00E3o, coletamos a localiza\u00E7\u00E3o do seu dispositivo para melhorar a precis\u00E3o da identifica\u00E7\u00E3o de pontos tur\u00EDsticos e mostrar lugares pr\u00F3ximos.
- Informa\u00E7\u00F5es do dispositivo: Coletamos o identificador do seu dispositivo para associar seus dados \u00E0 sua conta e permitir acesso como convidado.
- Informa\u00E7\u00F5es da conta: Se voc\u00EA fizer login com o Google, recebemos seu nome, endere\u00E7o de e-mail e foto de perfil do Google.
- Prefer\u00EAncia de idioma: Armazenamos sua prefer\u00EAncia de idioma selecionada para fornecer o aplicativo no idioma escolhido.

2. Como usamos suas informa\u00E7\u00F5es

- Para analisar fotos e identificar pontos tur\u00EDsticos usando IA
- Para fornecer informa\u00E7\u00F5es hist\u00F3ricas e culturais sobre pontos tur\u00EDsticos
- Para salvar seu di\u00E1rio de viagem e hist\u00F3rico de fotos
- Para personalizar sua experi\u00EAncia com base no idioma e localiza\u00E7\u00E3o
- Para melhorar a precis\u00E3o do nosso reconhecimento de IA

3. Armazenamento e seguran\u00E7a de dados

Seus dados s\u00E3o armazenados em nossos servidores seguros. Fotos e dados de pontos tur\u00EDsticos est\u00E3o associados ao seu dispositivo ou conta Google. Implementamos medidas de seguran\u00E7a apropriadas para proteger suas informa\u00E7\u00F5es pessoais.

4. Compartilhamento de dados

N\u00E3o vendemos suas informa\u00E7\u00F5es pessoais. Podemos compartilhar dados anonimizados e agregados para fins anal\u00EDticos. Suas fotos s\u00E3o processadas por nossos sistemas de IA e n\u00E3o s\u00E3o compartilhadas com terceiros.

5. Seus direitos

Voc\u00EA pode:
- Excluir sua conta e todos os dados associados entrando em contato conosco
- Alterar sua prefer\u00EAncia de idioma a qualquer momento
- Usar o aplicativo como convidado sem fornecer informa\u00E7\u00F5es pessoais

6. Privacidade das crian\u00E7as

Nosso aplicativo n\u00E3o \u00E9 direcionado a crian\u00E7as menores de 13 anos. N\u00E3o coletamos conscientemente informa\u00E7\u00F5es pessoais de crian\u00E7as.

7. Altera\u00E7\u00F5es nesta pol\u00EDtica

Podemos atualizar esta pol\u00EDtica de tempos em tempos. Notificaremos voc\u00EA sobre altera\u00E7\u00F5es significativas atrav\u00E9s do aplicativo.

8. Fale conosco

Se voc\u00EA tiver d\u00FAvidas sobre esta Pol\u00EDtica de Privacidade, entre em contato conosco em support@tripai.app"""
    }
}
