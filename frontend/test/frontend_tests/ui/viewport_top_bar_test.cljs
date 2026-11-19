;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.viewport-top-bar-test
  (:require
   ["react-dom/server" :as rds]
   [app.main.refs :as refs]
   [app.main.ui.workspace.viewport.top-bar :as top-bar]
   [app.util.i18n :as i18n]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [frontend-tests.helpers.mock :as mock]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(defn- render-bar
  [state]
  (let [state (l/atom state)]
    (with-redefs [refs/persistence (l/derived :persistence state)
                  refs/persistence-lock (l/derived :lock state)
                  refs/render-context-lost? (l/derived :context-lost state)
                  i18n/tr (mock/stub (fn [key] key))]
      (rds/renderToStaticMarkup (mf/element top-bar/view-only-bar* #js {})))))

(t/deftest a-terminal-save-failure-replaces-the-view-mode-message-and-exit
  (let [markup (render-bar {:lock {:read-only? false}
                            :persistence {:status :error :error {:type :server-error}}})]
    (t/is (str/includes? markup "workspace.save-failure.stopped"))
    (t/is (str/includes? markup "role=\"status\""))
    (t/is (not (str/includes? markup "workspace.top-bar.view-only")))
    (t/is (not (str/includes? markup "<button")))))

(t/deftest the-retry-message-stays-while-a-slow-save-is-in-flight
  (doseq [persistence [{:status :error :error {:type :service-unavailable}}
                       {:status :saving :recovering true}]]
    (let [markup (render-bar {:lock {:read-only? true} :persistence persistence})]
      (t/is (str/includes? markup "workspace.save-failure.retrying"))
      (t/is (not (str/includes? markup "<button"))))))

(t/deftest inspect-mode-keeps-its-exit-without-a-save-lock
  (doseq [status [:saved :retrying]]
    (let [markup (render-bar {:persistence {:status status}})]
      (t/is (str/includes? markup "workspace.top-bar.view-only"))
      (t/is (str/includes? markup "workspace.top-bar.read-only.done")))))

(t/deftest a-save-lock-prevents-exiting-view-mode-during-context-loss
  (let [markup (render-bar {:lock {:read-only? false}
                            :context-lost true
                            :persistence {:status :error :error {:type :server-error}}})]
    (t/is (str/includes? markup "workspace.save-failure.stopped"))
    (t/is (not (str/includes? markup "workspace.top-bar.read-only.done"))))

  (let [markup (render-bar {:context-lost true :persistence {:status :saved}})]
    (t/is (str/includes? markup "workspace.top-bar.webgl-context-lost"))
    (t/is (str/includes? markup "workspace.top-bar.webgl-context-lost.reload"))))
