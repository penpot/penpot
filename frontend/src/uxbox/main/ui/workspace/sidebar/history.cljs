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
            [rumext.alpha :as mf]
            [uxbox.util.router :as r]
            [uxbox.util.time :as dt]))

;; --- History Item (Component)

(mf/def history-item
  :mixins [mf/memo]
  :key-fn :id
  :render
  (fn [own {:keys [::selected] :as item}]
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
                   " (" (dt/timeago (:created-at item)) ")")]])))

;; --- History List (Component)

(mf/def history-list
  :mixins [mf/memo mf/reactive]
  :render
  (fn [own {:keys [selected items min-version] :as history}]
    (let [items (reverse (sort-by :version items))
          page (mf/react refs/selected-page)
          show-more? (pos? min-version)
          load-more #(st/emit! (udh/load-more))]
      [:ul.history-content
       (for [item items]
         (history-item (assoc item ::selectd selected)))
       (when show-more?
         [:li {:on-click load-more}
          [:a.btn-primary.btn-small
           "view more"]])])))

;; --- History Pinned List (Component)

(mf/def history-pinned-list
  :mixins [mf/memo]
  :render
  (fn [own {:keys [pinned selected] :as history}]
    [:ul.history-content
     (for [item (reverse (sort-by :version pinned))]
       (let [selected (= (:version item) selected)]
         (history-item (assoc item ::selected selected))))]))

;; --- History Toolbox (Component)

(mf/def history-toolbox
  :mixins [mf/memo mf/reactive]

  :init
  (fn [own page-id]
    (st/emit! (udh/initialize page-id))
    own)

  :will-unmount
  (fn [own]
    (st/emit! ::udh/stop-changes-watcher)
    own)

  :render
  (fn [own page-id]
    (let [history (mf/react refs/history)
          section (:section history :main)

          close #(st/emit! (dw/toggle-flag :document-history))
          main? (= section :main)
          pinned? (= section :pinned)

          show-main #(st/emit! (udh/select-section :main))
          show-pinned #(st/emit! (udh/select-section :pinned))]
      [:div.document-history.tool-window {}
       [:div.tool-window-bar {}
        [:div.tool-window-icon {} i/undo-history]
        [:span {} (tr "ds.settings.document-history")]
        [:div.tool-window-close {:on-click close} i/close]]
       [:div.tool-window-content {}
        [:ul.history-tabs {}
         [:li {:on-click show-main
               :class (when main? "selected")}
          (tr "ds.history.versions")]
         [:li {:on-click show-pinned
               :class (when pinned? "selected")}
          (tr "ds.history.pinned")]]
        (if (= section :pinned)
          (history-pinned-list history)
          (history-list history))]])))

;; --- History Dialog

(mf/def history-dialog
  :mixins [mf/memo mf/reactive]
  :render
  (fn [own]
    (let [history (mf/react refs/history)
          version (:selected history)
          on-accept #(st/emit! (udh/apply-selected-history))
          on-cancel #(st/emit! (udh/deselect-page-history))]
      (when (or version (:deselecting history))
        [:div.message-version
         {:class (when (:deselecting history) "hide-message")}
         [:span {} (tr "history.alert-message" (or version "00"))
          [:div.message-action {}
           [:a.btn-transparent {:on-click on-accept} (tr "ds.accept")]
           [:a.btn-transparent {:on-click on-cancel} (tr "ds.cancel")]]]]))))
