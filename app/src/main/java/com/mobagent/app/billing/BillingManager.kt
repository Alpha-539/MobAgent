package com.mobagent.app.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.mobagent.app.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Google Play obunalari. Xaridni TASDIQLASH (acknowledge) serverda bajariladi (/billing/verify),
 * shuning uchun ilova faqat tokenni yuboradi. Server javob bermasa, Worker'ning kunlik cron'i 3 kun ichida qayta urinadi.
 *
 * Play Console'da 3 ta obuna mahsuloti bo'lishi shart (har birida oylik "P1M" va yillik "P1Y" base plan):
 * mobagent_basic, mobagent_standard, mobagent_pro  (worker/src/plans.ts dagi PRODUCT_TO_PLAN bilan bir xil).
 */
object BillingManager : PurchasesUpdatedListener {
    val PRODUCT_IDS = listOf("mobagent_basic", "mobagent_standard", "mobagent_pro")

    /** Alohida sotib olinadigan credits (Play Console: bir martalik, iste'mol qilinadigan mahsulotlar; worker/src/plans.ts dagi CREDIT_PRODUCTS bilan bir xil). */
    val CREDIT_IDS = listOf("mobagent_credits_5", "mobagent_credits_15")

    data class CreditOffer(val productId: String, val name: String, val price: String)
    data class Offer(val productId: String, val offerToken: String, val price: String, val yearly: Boolean)
    data class PlanOffers(val productId: String, val name: String, val monthly: Offer?, val yearly: Offer?)

    private var client: BillingClient? = null
    private val details = mutableMapOf<String, ProductDetails>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Foydalanuvchiga ko'rsatiladigan xabarlar (xarid natijasi). */
    val messages: SharedFlow<String> = _messages

    private val _verified = MutableStateFlow(0)
    /** Server obunani tasdiqlagach oshadi — ekran tarifni qayta yuklaydi. */
    val verified: StateFlow<Int> = _verified

    fun init(context: Context) {
        if (client != null) return
        client = BillingClient.newBuilder(context.applicationContext)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
    }

    private suspend fun ready(): Boolean = suspendCancellableCoroutine { cont ->
        val c = client
        if (c == null) { cont.resume(false); return@suspendCancellableCoroutine }
        if (c.isReady) { cont.resume(true); return@suspendCancellableCoroutine }
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    /** Narxlar Google Play'dan (foydalanuvchi valyutasida). Billing mavjud bo'lmasa (masalan Play'siz o'rnatilgan APK) — null. */
    suspend fun loadOffers(): List<PlanOffers>? {
        if (!ready()) return null
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            PRODUCT_IDS.map {
                QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build()
            }
        ).build()
        val list: List<ProductDetails>? = suspendCancellableCoroutine { cont ->
            client!!.queryProductDetailsAsync(params) { br, res ->
                if (cont.isActive) cont.resume(if (br.responseCode == BillingClient.BillingResponseCode.OK) res.productDetailsList else null)
            }
        }
        if (list == null) return null
        list.forEach { details[it.productId] = it }
        return PRODUCT_IDS.mapNotNull { id ->
            val pd = details[id] ?: return@mapNotNull null
            // Faqat oddiy base plan narxi (sinov/chegirma takliflari emas): offerId == null
            val offers = pd.subscriptionOfferDetails.orEmpty().filter { it.offerId == null }.mapNotNull { o ->
                val phase = o.pricingPhases.pricingPhaseList.lastOrNull() ?: return@mapNotNull null
                Offer(id, o.offerToken, phase.formattedPrice, yearly = phase.billingPeriod == "P1Y")
            }
            PlanOffers(id, pd.name, offers.firstOrNull { !it.yearly }, offers.firstOrNull { it.yearly })
        }
    }

    /** Credits narxlari (bir martalik mahsulotlar). Mavjud bo'lmasa null. */
    suspend fun loadCreditOffers(): List<CreditOffer>? {
        if (!ready()) return null
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            CREDIT_IDS.map {
                QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.INAPP).build()
            }
        ).build()
        val list: List<ProductDetails>? = suspendCancellableCoroutine { cont ->
            client!!.queryProductDetailsAsync(params) { br, res ->
                if (cont.isActive) cont.resume(if (br.responseCode == BillingClient.BillingResponseCode.OK) res.productDetailsList else null)
            }
        }
        if (list == null) return null
        list.forEach { details[it.productId] = it }
        return CREDIT_IDS.mapNotNull { id ->
            val pd = details[id] ?: return@mapNotNull null
            val price = pd.oneTimePurchaseOfferDetails?.formattedPrice ?: return@mapNotNull null
            CreditOffer(id, pd.name, price)
        }
    }

    fun launchCredits(activity: Activity, offer: CreditOffer): Boolean {
        val pd = details[offer.productId] ?: return false
        val p = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(pd).build()
        val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(p)).build()
        return client?.launchBillingFlow(activity, flow)?.responseCode == BillingClient.BillingResponseCode.OK
    }

    fun launch(activity: Activity, offer: Offer): Boolean {
        val pd = details[offer.productId] ?: return false
        val p = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(pd).setOfferToken(offer.offerToken).build()
        val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(p)).build()
        return client?.launchBillingFlow(activity, flow)?.responseCode == BillingClient.BillingResponseCode.OK
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases?.forEach { handle(it, silent = false) }
            BillingClient.BillingResponseCode.USER_CANCELED -> {}
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> scope.launch { restore() }
            else -> _messages.tryEmit(tr("Xarid amalga oshmadi. Qayta urinib ko'ring."))
        }
    }

    private fun handle(p: Purchase, silent: Boolean) {
        if (p.purchaseState == Purchase.PurchaseState.PENDING) {
            if (!silent) _messages.tryEmit(tr("To'lov kutilmoqda. Tasdiqlangach tarif avtomatik yoqiladi."))
            return
        }
        if (p.purchaseState != Purchase.PurchaseState.PURCHASED) return
        val productId = p.products.firstOrNull() ?: return
        val isCredit = productId in CREDIT_IDS
        scope.launch {
            val err = if (isCredit) CloudApi.verifyCredits(productId, p.purchaseToken) else CloudApi.verify(productId, p.purchaseToken)
            if (err == null) {
                if (!silent) _messages.tryEmit(if (isCredit) tr("Credits qo'shildi") else tr("Tarif faollashtirildi"))
                _verified.value = _verified.value + 1
            } else if (!silent) {
                _messages.tryEmit(err)
            }
        }
    }

    /** Mavjud obunalarni serverga qayta yuboradi (qayta o'rnatish, yangi telefon, tasdiqlanmay qolgan xaridlar). Server takrorlanishga chidamli. */
    suspend fun restore() {
        if (!ready()) return
        for (type in listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP)) {
            client!!.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build()) { br, list ->
                if (br.responseCode == BillingClient.BillingResponseCode.OK) list.forEach { handle(it, silent = true) }
            }
        }
    }
}
