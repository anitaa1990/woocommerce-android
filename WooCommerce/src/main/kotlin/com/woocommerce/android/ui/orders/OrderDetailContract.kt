package com.woocommerce.android.ui.orders

import android.content.Context
import com.woocommerce.android.ui.base.BasePresenter
import com.woocommerce.android.ui.base.BaseView
import org.wordpress.android.fluxc.model.WCOrderModel
import org.wordpress.android.fluxc.model.WCOrderNoteModel
import org.wordpress.android.fluxc.model.WCOrderStatusModel
import org.wordpress.android.fluxc.model.order.OrderIdentifier

interface OrderDetailContract {
    interface Presenter : BasePresenter<View> {
        var orderModel: WCOrderModel?
        var orderIdentifier: OrderIdentifier?
        var isUsingCachedNotes: Boolean
        fun fetchOrder(remoteOrderId: Long)
        fun loadOrderDetail(orderIdentifier: OrderIdentifier, markComplete: Boolean)
        fun loadOrderNotes()
        fun doChangeOrderStatus(newStatus: String)
        fun pushOrderNote(noteText: String, isCustomerNote: Boolean)
        fun markOrderNotificationRead(context: Context, remoteNoteId: Long)
        fun getOrderStatusForStatusKey(key: String): WCOrderStatusModel
        fun getOrderStatusOptions(): Map<String, WCOrderStatusModel>
        fun refreshOrderStatusOptions()
    }

    interface View : BaseView<Presenter>, OrderActionListener {
        fun showOrderDetail(order: WCOrderModel?)
        fun showOrderNotes(notes: List<WCOrderNoteModel>)
        fun showOrderNotesSkeleton(show: Boolean)
        fun showAddOrderNoteScreen()
        fun updateOrderNotes(notes: List<WCOrderNoteModel>)
        fun setOrderStatus(newStatus: String)
        fun showChangeOrderStatusSnackbar(newStatus: String)
        fun showNotesErrorSnack()
        fun showAddOrderNoteSnack()
        fun showAddOrderNoteErrorSnack()
        fun showOrderStatusChangedError()
        fun markOrderStatusChangedSuccess()
        fun markOrderStatusChangedFailed()
        fun showLoadOrderProgress(show: Boolean)
        fun showLoadOrderError()
        fun refreshOrderStatus()
    }
}
