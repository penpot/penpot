;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.context-menu-prototype-test
  (:require
   ["react-dom/server" :as rds]
   [app.common.uuid :as uuid]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.workspace.context-menu :as cm]
   [app.util.i18n :as i18n]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [frontend-tests.helpers.mock :as mock]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(defn- render-menu
  "Render the prototype entries for `frame`, returning the markup and the
  events emitted while rendering."
  [flows frame]
  (let [events (atom [])
        markup (with-redefs [refs/workspace-page-flows (l/atom flows)
                             refs/options-mode-global (l/atom :prototype)
                             st/emit! (mock/stub (fn [& emitted] (swap! events into emitted)))
                             i18n/tr (mock/stub (fn [key] key))]
                 (rds/renderToStaticMarkup
                  (mf/element @#'cm/context-menu-prototype* #js {:shapes [frame]})))]
    {:markup markup :events @events}))

(t/deftest a-flow-start-board-menu-offers-removing-the-flow-without-removing-it
  (let [frame-id (uuid/next)
        flow-id  (uuid/next)
        flows    {flow-id {:id flow-id :name "Flow 1" :starting-frame frame-id}}
        {:keys [markup events]} (render-menu flows {:id frame-id :type :frame})]
    (t/is (str/includes? markup "workspace.shape.menu.delete-flow-start"))
    (t/is (= [] events))))

(t/deftest a-plain-board-menu-offers-a-flow-start
  (let [{:keys [markup events]} (render-menu {} {:id (uuid/next) :type :frame})]
    (t/is (str/includes? markup "workspace.shape.menu.flow-start"))
    (t/is (= [] events))))
