;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.webgl-unavailable-modal
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.common :as dcm]
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as k]
   [goog.events :as events]
   [rumext.v2 :as mf])
  (:import goog.events.EventType))

(defn- close-and-go-dashboard
  []
  (st/emit! (modal/hide)
            (dcm/go-to-dashboard-recent)))

(def ^:const webgl-troubleshooting-url "https://help.penpot.app/user-guide/first-steps/troubleshooting-webgl/")

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(mf/defc webgl-unavailable-modal*
  {::mf/register modal/components
   ::mf/register-as :webgl-unavailable}
  [_]

  (let [handle-keydown (fn [event]
                         (when (k/esc? event)
                           (dom/stop-propagation event)
                           (close-and-go-dashboard)))]
    (mf/with-effect []
      (let [key (events/listen js/document EventType.KEYDOWN handle-keydown)]
        (fn []
          (events/unlistenByKey key))))

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog)}
      [:header {:class (stl/css :modal-header)}
       [:> icon-button* {:on-click close-and-go-dashboard
                         :class (stl/css :modal-close-btn)
                         :icon i/close
                         :variant "action"
                         :size "medium"
                         :aria-label (tr "labels.close")}]
       [:> heading* {:level 2 :typography t/title-medium}
        (tr "webgl.modals.webgl-unavailable.title")]]

      [:section {:class (stl/css :modal-content)}
       [:> text* {:as "p" :typography t/body-medium}
        (tr "webgl.modals.webgl-unavailable.message")]
       [:> text* {:as "p" :typography t/body-medium}
        (tr "webgl.modals.webgl-unavailable.troubleshooting.before")
        [:a {:href webgl-troubleshooting-url
             :target "_blank"
             :rel "noopener noreferrer"
             :class (stl/css :link)}
         (tr "webgl.modals.webgl-unavailable.troubleshooting.link")]
        (tr "webgl.modals.webgl-unavailable.troubleshooting.after")]]

      [:footer {:class (stl/css :modal-footer)}
       [:> button* {:on-click close-and-go-dashboard
                    :variant "primary"}
        (tr "webgl.modals.webgl-unavailable.cta")]]]]))
