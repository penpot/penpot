;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.history
  (:require
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.history :as udh]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.util.data :refer [read-string]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :refer (tr)]
   [uxbox.util.router :as r]
   [uxbox.util.time :as dt]))

;; --- History Item (Component)

(mf/defc history-item
  [{:keys [item selected?] :as props}]
  (letfn [(on-select [event]
            (dom/prevent-default event)
            (st/emit! (udh/select (:version item))))
          (on-pinned [event]
            (dom/prevent-default event)
            (dom/stop-propagation event)
            (let [item (assoc item
                              :label "no label"
                              :pinned (not (:pinned item)))]
              (st/emit! (udh/update-history-item item))))]
    [:li {:class (when selected? "current")
          :on-click on-select}
     [:div.pin-icon {:on-click on-pinned
                     :class (when (:pinned item) "selected")}
      i/pin]
     [:span (str "Version " (:version item)
                 " (" (dt/timeago (:created-at item)) ")")]]))

;; --- History List (Component)

(mf/defc history-list
  [{:keys [history] :as props}]
  (let [items (reverse (sort-by :version (:items history)))
        show-more? (pos? (:min-version history))
        load-more #(st/emit! udh/load-more)]
    [:ul.history-content
     (for [item items]
       [:& history-item {:item item
                         :key (:id item)
                         :selected? (= (:selected history)
                                       (:version item))}])
     (when show-more?
       [:li {:on-click load-more}
        [:a.btn-primary.btn-small "view more"]])]))

;; --- History Pinned List (Component)

(mf/defc history-pinned-list
  [{:keys [history] :as props}]
  [:ul.history-content
   (for [item (reverse (sort-by :version (:pinned history)))]
     [:& history-item {:item item
                       :key (:id item)
                       :selected? (= (:selected history)
                                     (:version item))}])])

;; --- History Toolbox (Component)

(mf/defc history-toolbox
  [props]
  (let [history (mf/deref refs/history)
        section (mf/use-state :main)
        close #(st/emit! (dw/toggle-flag :history))
        main? (= @section :main)
        pinned? (= @section :pinned)
        show-main #(st/emit! (udh/select-section :main))
        show-pinned #(st/emit! (udh/select-section :pinned))]
    [:div.document-history.tool-window
     [:div.tool-window-bar
      [:div.tool-window-icon i/undo-history]
      [:span (tr "ds.settings.document-history")]
      [:div.tool-window-close {:on-click close} i/close]]
     [:div.tool-window-content
      [:ul.history-tabs
       [:li {:on-click #(reset! section :main)
             :class (when main? "selected")}
        (tr "ds.history.versions")]
       [:li {:on-click #(reset! section :pinned)
             :class (when pinned? "selected")}
        (tr "ds.history.pinned")]]
      (if (= @section :pinned)
        [:& history-pinned-list {:history history}]
        [:& history-list {:history history}])]]))

;; --- History Dialog

(mf/defc history-dialog
  [props]
  (let [history (mf/deref refs/history)
        version (:selected history)
        on-accept #(st/emit! udh/apply-selected)
        on-cancel #(st/emit! udh/deselect)]
    (when (or version (:deselecting history))
      [:div.message-version
       {:class (when (:deselecting history) "hide-message")}
       [:span (tr "history.alert-message" (or version "00"))
        [:div.message-action
         [:a.btn-transparent {:on-click on-accept} (tr "ds.accept")]
         [:a.btn-transparent {:on-click on-cancel} (tr "ds.cancel")]]]])))

