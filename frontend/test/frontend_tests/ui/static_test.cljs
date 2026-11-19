;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.static-test
  (:require
   [app.main.ui.static :as static]
   [cljs.test :as t :include-macros true]))

(t/deftest screen-kind-classifies-known-screens
  ;; Regression test (penpot/penpot#11224): the exception page shows the
  ;; REQUEST ACCESS modal only when it recognizes the screen the failing
  ;; URL belongs to. The `screen` query param carries the route name, so
  ;; the viewer is "viewer" and every team dashboard is "dashboard-*".
  (t/testing "workspace"
    (t/is (= :workspace (static/screen-kind {:screen "workspace"}))))

  (t/testing "bare dashboard prefix also classifies"
    ;; No such route exists (routes are always "dashboard-*"), and the
    ;; modal still needs loaded team/file info, so this is harmless and
    ;; matches the prefix convention used in routes.cljs.
    (t/is (= :dashboard (static/screen-kind {:screen "dashboard"}))))

  (t/testing "viewer"
    (t/is (= :viewer (static/screen-kind {:screen "viewer"}))))

  (t/testing "dashboard variants"
    (doseq [screen ["dashboard-recent"
                    "dashboard-members"
                    "dashboard-files"
                    "dashboard-settings"]]
      (t/is (= :dashboard (static/screen-kind {:screen screen}))
            (str "screen: " screen)))))

(t/deftest screen-kind-ignores-unknown-screens
  ;; Unknown, missing, or auth screens are not request-access candidates:
  ;; the exception page must keep showing the plain not-found page.
  (doseq [params [nil
                  {}
                  {:screen "auth-login"}
                  {:screen "settings-profile"}
                  ;; The "view" literal never was a real screen name; the
                  ;; exception page compared against it before the fix.
                  {:screen "view"}]]
    (t/is (nil? (static/screen-kind params))
          (str "params: " (pr-str params)))))

(t/deftest screen-kind-tolerates-duplicate-screen-params
  ;; A repeated query param arrives as a vector; the classifier reads the
  ;; scalar the same way the router does instead of crashing on it.
  (t/is (= :dashboard
           (static/screen-kind {:screen ["dashboard-recent" "dashboard-recent"]}))))
