;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.workspace.sidebar.history
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.locales :refer (tr)]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.shapes :as shapes]
            [uxbox.library :as library]
            [uxbox.data.workspace :as dw]
            [uxbox.data.pages :as udp]
            [uxbox.data.history :as udh]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.messages :as msg]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.util.datetime :as dt]
            [uxbox.util.data :refer (read-string)]
            [uxbox.util.dom :as dom]))

;; --- Lenses

(def ^:const history-l
  (as-> (l/in [:workspace :history]) $
    (l/focus-atom $ st/state)))

;; --- Components

(defn history-item-render
  [own item selected]
  (letfn [(on-select [event]
            (dom/prevent-default event)
            (rs/emit! (udh/select-page-history item)))
          (on-pinned [event]
            (dom/prevent-default event)
            (dom/stop-propagation event)
            (let [item (assoc item
                              :label "no label"
                              :pinned (not (:pinned item)))]
              (rs/emit! (udh/update-history-item item))))]
    (let [selected? (= (:id item) selected)]
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

(defn history-list-render
  [own page history]
  (letfn [(on-select [event]
            (dom/prevent-default event)
            (rs/emit! (udh/discard-selected-history (:id page))))

          (on-load-more [event]
            (dom/prevent-default event)
            (let [since (:min-version history)
                  params {:since since}]
              (rs/emit! (udh/fetch-page-history (:id page) params))))]

    (let [selected (:selected history)
          show-more? (pos? (:min-version history))]
      (html
       [:ul.history-content
        [:li {:class (when-not selected "current")
              :on-click on-select}
         [:div.pin-icon i/pin]
         [:span (str "Version " (:version page) " (current)")]]
        (for [item (:items history)]
          (-> (history-item item selected)
              (rum/with-key (str (:id item)))))
        (if show-more?
          [:li {:on-click on-load-more}
           [:a.btn-primary.btn-small "view more"]])]))))

(defn history-list-will-update
  [own]
  (let [[page history] (:rum/props own)]
    (if (:selected history)
      (let [selected (->> (:items history)
                          (filter #(= (:selected history) (:id %)))
                          (first))]
        (msg/dialog
         :message (tr "history.alert-message" (:version selected))
         :on-accept #(rs/emit! (udh/apply-selected-history (:id page)))
         :on-cancel #(rs/emit! (udh/discard-selected-history (:id page)))))
      (msg/close))
    own))

(def history-list
  (mx/component
   {:render history-list-render
    :will-update history-list-will-update
    :name "history-list"
    :mixins [mx/static]}))

(defn history-pinned-list-render
  [own history]
  (html
   [:ul.history-content
    (for [item (:pinned-items history)]
      (-> (history-item item (:selected history))
          (rum/with-key (str (:id item)))))]))

    ;; [:li
    ;;  [:span "Version 02/02/2016 12:33h"]
    ;;  [:div.page-actions
    ;;   [:a i/pencil]
    ;;   [:a i/trash]]]]))

(def history-pinned-list
  (mx/component
   {:render history-pinned-list-render
    :name "history-pinned-list"
    :mixins [mx/static]}))

(defn history-toolbox-render
  [own]
  (let [local (:rum/local own)
        page (rum/react wb/page-l)
        history (rum/react history-l)
        section (:section @local :main)
        close #(rs/emit! (dw/toggle-flag :document-history))
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

(def ^:static history-toolbox
  (mx/component
   {:render history-toolbox-render
    :name "document-history-toolbox"
    :mixins [mx/static rum/reactive (mx/local)]}))
