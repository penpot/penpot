;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.viewer
  (:require
   [beicon.core :as rx]
   [goog.events :as events]
   [goog.object :as gobj]
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.store :as st]
   [uxbox.common.exceptions :as ex]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.components.dropdown :refer [dropdown]]
   [uxbox.main.data.viewer :as dv]
   [uxbox.main.ui.viewer.header :refer [header]]
   [uxbox.main.ui.viewer.thumbnails :refer [thumbnails-panel frame-svg]]
   [uxbox.util.dom :as dom]
   [uxbox.main.ui.hooks :as hooks]
   [uxbox.util.data :refer [classnames]]
   [uxbox.util.i18n :as i18n :refer [t tr]]
   [uxbox.util.math :as mth]
   [uxbox.util.router :as rt])
  (:import goog.events.EventType
           goog.events.KeyCodes))

(mf/defc main-panel
  [{:keys [data zoom index]}]
  (let [frames  (:frames data [])
        objects (:objects data)
        frame   (get frames index)]

    (when-not frame
      (ex/raise :type :not-found
                :hint "Frame not found"))

    [:section.viewer-preview
     [:& frame-svg {:frame frame :zoom zoom :objects objects}]]))

(mf/defc viewer-content
  [{:keys [data local index] :as props}]
  (let [container (mf/use-ref)

        [toggle-fullscreen fullscreen?] (hooks/use-fullscreen container)

        on-mouse-wheel
        (fn [event]
          (when (kbd/ctrl? event)
            ;; Disable browser zoom with ctrl+mouse wheel
            (dom/prevent-default event)))

        on-mount
        (fn []
          ;; bind with passive=false to allow the event to be cancelled
          ;; https://stackoverflow.com/a/57582286/3219895
          (let [key1 (events/listen goog/global EventType.WHEEL
                                    on-mouse-wheel #js {"passive" false})]
            (fn []
              (events/unlistenByKey key1))))]

    (mf/use-effect on-mount)
    (hooks/use-shortcuts dv/shortcuts)

    [:div.viewer-layout {:class (classnames :fullscreen fullscreen?)
                         :ref container}

     [:& header {:data data
                 :toggle-fullscreen toggle-fullscreen
                 :fullscreen? fullscreen?
                 :local local
                 :index index}]
     [:div.viewer-content
      (when (:show-thumbnails local)
        [:& thumbnails-panel {:index index
                              :data data}])
      [:& main-panel {:data data
                      :zoom (:zoom local)
                      :index index}]]]))


;; --- Component: Viewer Page

(def viewer-data-ref
  (-> (l/key :viewer-data)
      (l/derive st/state)))

(def viewer-local-ref
  (-> (l/key :viewer-local)
      (l/derive st/state)))

(mf/defc viewer-page
  [{:keys [page-id index] :as props}]
  (mf/use-effect (mf/deps page-id) #(st/emit! (dv/initialize page-id)))
  (let [data (mf/deref viewer-data-ref)
        local (mf/deref viewer-local-ref)]
    (when data
      [:& viewer-content {:index index
                          :local local
                          :data data}])))
