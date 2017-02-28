;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.history
  (:require [lentes.core :as l]
            [potok.core :as ptk]
            [uxbox.main.store :as st]
            [uxbox.main.refs :as refs]
            [uxbox.main.data.workspace :as dw]
            [uxbox.main.data.pages :as udp]
            [uxbox.main.data.history :as udh]
            [uxbox.builtins.icons :as i]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.router :as r]
            [uxbox.util.data :refer (read-string)]
            [uxbox.util.dom :as dom]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.time :as dt]))


;; --- Lenses

(def history-ref
  (as-> (l/in [:workspace :history]) $
    (l/derive $ st/state)))

;; --- History Item (Component)

(mx/defc history-item
  {:mixins [mx/static]}
  [item selected]
  (letfn [(on-select [event]
            (dom/prevent-default event)
            (st/emit! (udh/select-page-history (:version item))))
          (on-pinned [event]
            (dom/prevent-default event)
            (dom/stop-propagation event)
            (let [item (assoc item
                              :label "no label"
                              :pinned (not (:pinned item)))]
              (st/emit! (udh/update-history-item item))))]
    (let [selected? (= (:version item) selected)]
      [:li {:class (when selected? "current") :on-click on-select}
       [:div.pin-icon {:on-click on-pinned
                       :class (when (:pinned item) "selected")}
        i/pin]
       [:span (str "Version " (:version item)
                   " (" (dt/timeago (:created-at item)) ")")]])))

;; --- History List (Component)

(mx/defc history-list
  {:mixins [mx/static]}
  [page history]
  (letfn [(on-select [event]
            (dom/prevent-default event)
            (st/emit! (udh/deselect-page-history (:id page))))

          (on-load-more [event]
            (dom/prevent-default event)
            (let [since (:min-version history)
                  params {:since since}]
              (st/emit! (udh/fetch-page-history (:id page) params))))]

    (let [selected (:selected history)
          show-more? (pos? (:min-version history))]
      [:ul.history-content
       [:li {:class (when-not selected "current")
             :on-click on-select}
        [:div.pin-icon i/pin]
         [:span (str "Version " (:version page) " (current)")]]
       (for [version (:items history)
             :let [item (get-in history [:by-version version])]]
         (-> (history-item item selected)
             (mx/with-key (str (:id item)))))
       (if show-more?
         [:li {:on-click on-load-more}
          [:a.btn-primary.btn-small
           "view more"]])])))

;; --- History Pinned List (Component)

(mx/defc history-pinned-list
  {:mixins [mx/static]}
  [history]
  [:ul.history-content
   (for [version (:pinned-items history)
         :let [item (get-in history [:by-version version])]]
     (-> (history-item item (:selected history))
         (mx/with-key (str (:id item)))))])

;; --- History Toolbox (Component)

(mx/defcs history-toolbox
  {:mixins [mx/static mx/reactive (mx/local)]}
  [{:keys [rum/local] :as own}]
  (let [page (mx/react refs/selected-page)
        history (mx/react refs/history)
        section (:section @local :main)
        close #(st/emit! (dw/toggle-flag :document-history))
        main? (= section :main)
        pinned? (= section :pinned)
        show-main #(swap! local assoc :section :main)
        show-pinned #(swap! local assoc :section :pinned)]
    [:div.document-history.tool-window
     [:div.tool-window-bar
      [:div.tool-window-icon i/undo-history]
      [:span (tr "ds.document-history")]
      [:div.tool-window-close {:on-click close} i/close]]
     [:div.tool-window-content
      [:ul.history-tabs
       [:li {:on-click show-main
             :class (when main? "selected")}
        "History"]
       [:li {:on-click show-pinned
             :class (when pinned? "selected")}
        "Pinned"]]
      (if (= section :pinned)
        (history-pinned-list history)
        (history-list page history))]]))

;; --- History Dialog

(mx/defc history-dialog
  {:mixins [mx/static mx/reactive]}
  [page]
  (let [history (mx/react refs/history)
        version (:selected history)
        on-accept #(st/emit! (udh/apply-selected-history page))
        on-cancel #(st/emit! (udh/deselect-page-history page))]
    (when (or version (:deselecting history))
      [:div.message-version
       {:class (when (:deselecting history) "hide-message")}
       [:span (tr "history.alert-message" (or version "00"))
        [:div.message-action
         [:a.btn-transparent {:on-click on-accept} "Accept"]
         [:a.btn-transparent {:on-click on-cancel} "Cancel"]]]])))
