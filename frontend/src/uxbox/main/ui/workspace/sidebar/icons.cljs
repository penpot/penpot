;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.icons
  (:require
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.icons :as di]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.dashboard.icons :as icons]
   [uxbox.main.ui.shapes.icon :as icon]
   [uxbox.util.data :refer [read-string]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :as i18n :refer [t]]
   [uxbox.util.router :as r]))

(mf/defc icons-collections
  [{:keys [collections value on-change] :as props}]

  [:div.figures-catalog
   ;; extract component: set selector
   [:select.input-select.small {:on-change on-change
                                :value (pr-str value)}
    [:option {:value (pr-str nil)} "Storage"]
    (for [coll collections]
      [:option {:key (str "icon-coll" (:id coll))
                :value (pr-str (:id coll))}
       (:name coll)])]])

(mf/defc icons-list
  [{:keys [collection-id] :as props}]
  (let [icons-iref (mf/use-memo
                    {:fn #(icons/make-icons-iref collection-id)
                     :deps (mf/deps collection-id)})
        icons (mf/deref icons-iref)

        on-select
        (fn [event data]
          (st/emit! (dw/select-for-drawing :icon data)))]

    (mf/use-effect
     {:fn #(st/emit! (di/fetch-icons collection-id))
      :deps (mf/deps collection-id)})

    (for [icon icons
          :let [selected? (= nil #_(:drawing local) icon)]]
      [:div.figure-btn {:key (str (:id icon))
                        :class (when selected? "selected")
                        :on-click #(on-select % icon)
                        }
       [:& icon/icon-svg {:shape icon}]])))

;; --- Icons (Component)

(mf/defc icons-toolbox
  [props]
  (let [locale (i18n/use-locale)
        local (mf/deref refs/workspace-local)
        collections (mf/deref icons/collections-iref)
        collection (first collections)

        on-close
        (fn [event]
          (st/emit! (dw/toggle-layout-flag :icons)))

        on-change
        (fn [event]
          (let [value (read-string (dom/event->value event))]
            (st/emit! (dw/select-for-drawing nil)
                      #_(dw/select-icons-toolbox-collection value))))
        ]

    (mf/use-effect
     {:fn #(st/emit! di/fetch-collections)})

    [:div#form-figures.tool-window
     [:div.tool-window-bar
      [:div.tool-window-icon i/icon-set]
      [:span (t locale "workspace.sidebar.icons")]
      [:div.tool-window-close #_{:on-click on-close} i/close]]
     [:div.tool-window-content
      [:& icons-collections {:collections collections
                             :value (:id collection)
                             :on-change (constantly nil)
                             }]
      [:& icons-list {:collection-id nil}]]]))
