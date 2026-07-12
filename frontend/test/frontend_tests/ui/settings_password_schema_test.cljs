;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.ui.settings-password-schema-test
  (:require
   [app.common.schema :as sm]
   [app.main.ui.settings.password :as passwd]
   [cljs.test :as t :include-macros true]
   [malli.core :as m]))

(def ^:private new-password "a-long-enough-password")

(defn- params
  [password-old]
  {:password-old password-old
   :password-1 new-password
   :password-2 new-password})

(defn- valid?
  [data]
  (sm/validate passwd/schema:password-form data))

(defn- error-codes
  "Maps every schema problem to [field error-code], mirroring how
  app.common.schema.messages resolves the message shown next to an input."
  [data]
  (->> (:errors (sm/explain passwd/schema:password-form data))
       (map (fn [{:keys [in schema]}]
              (let [props  (m/properties schema)
                    tprops (m/type-properties schema)]
                [(or (:error/field props) (first in))
                 (or (:error/code props) (:error/code tprops))])))
       (into {})))

(t/deftest short-old-password-is-accepted
  (t/testing "an existing password shorter than the 8 char policy can still be typed in"
    (t/is (true? (valid? (params "short"))))
    (t/is (empty? (error-codes (params "short"))))))

(t/deftest single-char-old-password-is-accepted
  (t/is (true? (valid? (params "x")))))

(t/deftest unicode-old-password-is-accepted
  (t/is (true? (valid? (params "🔑é"))))
  (t/is (true? (valid? (params "  hunter2  ")))))

(t/deftest empty-old-password-is-rejected
  (t/is (false? (valid? (params ""))))
  (t/is (contains? (error-codes (params "")) :password-old)))

(t/deftest blank-old-password-is-rejected
  (t/is (false? (valid? (params "   "))))
  (t/is (contains? (error-codes (params "   ")) :password-old)))

(t/deftest missing-old-password-is-rejected
  (t/is (false? (valid? (dissoc (params "short") :password-old)))))

(t/deftest overlong-old-password-is-rejected
  (t/is (false? (valid? (params (apply str (repeat 501 "a")))))))

(t/deftest short-new-password-is-rejected
  (t/testing "the 8 char policy still applies to the new password"
    (let [data {:password-old "short" :password-1 "abc" :password-2 "abc"}]
      (t/is (false? (valid? data)))
      (t/is (= "errors.password-too-short"
               (get (error-codes data) :password-1))))))

(t/deftest confirmation-mismatch-is-rejected
  (let [data {:password-old "short"
              :password-1 new-password
              :password-2 "another-long-password"}]
    (t/is (false? (valid? data)))
    (t/is (= "errors.password-invalid-confirmation"
             (get (error-codes data) :password-2)))))
