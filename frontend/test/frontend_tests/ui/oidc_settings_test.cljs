;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.oidc-settings-test
  (:require
   ["jsdom" :refer [JSDOM]]
   ["react" :as react]
   ["react-dom/server" :as server]
   [app.common.uuid :as uuid]
   [app.main.store :as st]
   [app.main.ui.settings :as settings]
   [app.main.ui.settings.profile :as profile]
   [app.main.ui.settings.sidebar :as sidebar]
   [app.util.globals :as globals]
   [app.util.i18n :as i18n]
   [cljs.test :as t :include-macros true]
   [clojure.string :as str]))

(defn- render
  [component account props]
  (let [previous @st/state
        browser  (JSDOM. "<!doctype html><html><body></body></html>" #js {:url "http://localhost/"})
        document (.-document js/globalThis)
        window   (.-window js/globalThis)]
    (try
      (set! (.-document js/globalThis) (.. browser -window -document))
      (set! (.-window js/globalThis) (.-window browser))
      (swap! st/state assoc :profile account)
      (with-redefs [globals/document (.. browser -window -document)
                    globals/window (.-window browser)
                    i18n/tr (fn ([key] key) ([key & _] key))]
        (server/renderToStaticMarkup (react/createElement component props)))
      (finally
        (reset! st/state previous)
        (set! (.-document js/globalThis) document)
        (set! (.-window js/globalThis) window)
        (.close (.-window browser))))))

(t/deftest oidc-settings-hide-local-credential-editors
  (doseq [oidc? [false true]]
    (let [account {:id (uuid/random) :fullname "Example User"
                   :email "user@example.com" :is-oidc oidc?
                   :default-team-id (uuid/random)}
          menu    (render sidebar/sidebar-content* account
                          #js {:profile account :section :settings-profile})
          form    (render profile/profile-page* account #js {})
          direct  (render settings/settings* account
                          #js {:route {:data {:name :settings-password}}})]
      (t/is (= (not oidc?) (str/includes? menu "labels.password")))
      (t/is (= (not oidc?) (str/includes? form "dashboard.change-email")))
      (t/is (str/includes? form "user@example.com"))
      (t/is (= (not oidc?) (str/includes? direct "password-section-title")))
      (t/is (= oidc? (str/includes? direct "profile-section-title"))))))
