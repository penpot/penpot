;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.history
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [potok.core :as ptk]
            [uxbox.main.store :as st]
            [uxbox.main.refs :as refs]
            [uxbox.main.data.workspace :as dw]
            [uxbox.main.data.pages :as udp]
            [uxbox.main.data.history :as udh]
            [uxbox.main.ui.icons :as i]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.router :as r]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.time :as dt]
            [uxbox.util.data :refer (read-string)]
            [uxbox.util.dom :as dom]))

;; --- Lenses

(def history-ref
  (as-> (l/in [:workspace :history]) $
    (l/derive $ st/state)))

;; --- History Item (Component)

(defn history-item-render
  [own item selected]
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
      (html
       [:li {:class (when selected? "current") :on-click on-select}
        [:div.pin-icon {:on-click on-pinned
                        :class (when (:pinned item) "selected")}
         i/pin]
        [:span (str "Version " (:version item)
                    " (" (dt/timeago (:created-at item)) ")")]]))))

(def history-item
  (mx/component
   {:render history-item-render
    :name "history-item"
    :mixins [mx/static]}))

;; --- History List (Component)

(defn history-list-render
  [own page history]
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
      (html
       [:ul.history-content
        [:li {:class (when-not selected "current")
              :on-click on-select}
         [:div.pin-icon i/pin]
         [:span (str "Version " (:version page) " (current)")]]
        (for [version (:items history)
              :let [item (get-in history [:by-version version])]]
          (-> (history-item item selected)
              (rum/with-key (str (:id item)))))
        (if show-more?
          [:li {:on-click on-load-more}
           [:a.btn-primary.btn-small
            "view more"]])]))))

(def history-list
  (mx/component
   {:render history-list-render
    :name "history-list"
    :mixins [mx/static]}))

;; --- History Pinned List (Component)

(defn history-pinned-list-render
  [own history]
  (html
   [:ul.history-content
    (for [version (:pinned-items history)
          :let [item (get-in history [:by-version version])]]
      (-> (history-item item (:selected history))
          (rum/with-key (str (:id item)))))]))

(def history-pinned-list
  (mx/component
   {:render history-pinned-list-render
    :name "history-pinned-list"
    :mixins [mx/static]}))

;; --- History Toolbox (Component)

(defn history-toolbox-render
  [own]
  (let [local (:rum/local own)
        page (mx/react refs/selected-page)
        history (mx/react history-ref)
        section (:section @local :main)
        close #(st/emit! (dw/toggle-flag :document-history))
        main? (= section :main)
        pinned? (= section :pinned)
        show-main #(swap! local assoc :section :main)
        show-pinned #(swap! local assoc :section :pinned)]
    (html
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
         (history-list page history))]])))

(def history-toolbox
  (mx/component
   {:render history-toolbox-render
    :name "document-history-toolbox"
    :mixins [mx/static mx/reactive (mx/local)]}))

;; --- History Dialog

(defn history-dialog-render
  [own page]
  (let [history (mx/react history-ref)
        version (:selected history)
        on-accept #(st/emit! (udh/apply-selected-history page))
        on-cancel #(st/emit! (udh/deselect-page-history page))]
    (when (or version (:deselecting history))
      (html
       [:div.message-version
        {:class (when (:deselecting history) "hide-message")}
        [:span (tr "history.alert-message" (or version "00"))
         [:div.message-action
          [:a.btn-transparent {:on-click on-accept} "Accept"]
          [:a.btn-transparent {:on-click on-cancel} "Cancel"]]]]))))

(def history-dialog
  (mx/component
   {:render history-dialog-render
    :name "history-dialog"
    :mixins [mx/static mx/reactive]}))

