;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.history
  (:require [uxbox.builtins.icons :as i]
            [uxbox.main.refs :as refs]
            [uxbox.main.store :as st]
            [uxbox.main.data.history :as udh]
            [uxbox.main.data.pages :as udp]
            [uxbox.main.data.workspace :as dw]
            [uxbox.util.data :refer [read-string]]
            [uxbox.util.dom :as dom]
            [uxbox.util.i18n :refer [tr]]
            [rumext.core :as mx :include-macros true]
            [uxbox.util.router :as r]
            [uxbox.util.time :as dt]))

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
    [:li {:class (when (= selected (:version item)) "current")
          :on-click on-select}
     [:div.pin-icon {:on-click on-pinned
                     :class (when (:pinned item) "selected")}
      i/pin]
     [:span (str "Version " (:version item)
                 " (" (dt/timeago (:created-at item)) ")")]]))

;; --- History List (Component)

(mx/defc history-list
  {:mixins [mx/static mx/reactive]}
  [{:keys [selected items min-version] :as history}]
  (let [items (reverse (sort-by :version items))
        page (mx/react refs/selected-page)
        show-more? (pos? min-version)
        load-more #(st/emit! (udh/load-more))]
    [:ul.history-content
     (for [item items
           :let [current? (= (:version item) (:version page))]]
       (-> (history-item item selected current?)
           (mx/with-key (str (:id item)))))
     (when show-more?
       [:li {:on-click load-more}
        [:a.btn-primary.btn-small
         "view more"]])]))

;; --- History Pinned List (Component)

(mx/defc history-pinned-list
  {:mixins [mx/static]}
  [{:keys [pinned selected] :as history}]
  [:ul.history-content
   (for [item (reverse (sort-by :version pinned))
         :let [selected? (= (:version item) selected)]]
     (-> (history-item item selected?)
         (mx/with-key (str (:id item)))))])

;; --- History Toolbox (Component)


(defn- history-toolbox-will-mount
  [own]
  (let [[page-id] (:rum/args own)]
    (st/emit! (udh/initialize page-id))
    own))

(defn- history-toolbox-did-remount
  [oldown own]
  (let [[old-page-id] (:rum/args oldown)
        [new-page-id] (:rum/args own)]
    (when-not (= old-page-id new-page-id)
      (st/emit! ::udh/stop-changes-watcher
                (udh/initialize new-page-id)))
    own))

(defn- history-toolbox-will-unmount
  [own]
  (st/emit! ::udh/stop-changes-watcher)
  own)

(mx/defc history-toolbox
  {:mixins [mx/static mx/reactive]
   :will-mount history-toolbox-will-mount
   :will-unmount history-toolbox-will-unmount
   :did-remount history-toolbox-did-remount}
  [_]
  (let [history (mx/react refs/history)
        section (:section history :main)

        close #(st/emit! (dw/toggle-flag :document-history))
        main? (= section :main)
        pinned? (= section :pinned)

        show-main #(st/emit! (udh/select-section :main))
        show-pinned #(st/emit! (udh/select-section :pinned))]
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
        (history-list history))]]))

;; --- History Dialog

(mx/defc history-dialog
  {:mixins [mx/static mx/reactive]}
  []
  (let [history (mx/react refs/history)
        version (:selected history)
        on-accept #(st/emit! (udh/apply-selected-history))
        on-cancel #(st/emit! (udh/deselect-page-history))]
    (when (or version (:deselecting history))
      [:div.message-version
       {:class (when (:deselecting history) "hide-message")}
       [:span (tr "history.alert-message" (or version "00"))
        [:div.message-action
         [:a.btn-transparent {:on-click on-accept} "Accept"]
         [:a.btn-transparent {:on-click on-cancel} "Cancel"]]]])))
