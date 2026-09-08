;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-plugins-test
  (:require
   [app.common.uuid :as uuid]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.profile :as profile]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(def ^:private plugin-id-1 (str (uuid/next)))
(def ^:private plugin-id-2 (str (uuid/next)))

(def ^:private valid-plugin
  {:plugin-id plugin-id-1
   :name "Test Plugin"
   :description "A test plugin"
   :host "https://example.com"
   :code "(function() { console.log('hello'); })()"
   :icon "icon.svg"
   :permissions #{"content:read" "content:write"}})

(t/deftest add-profile-plugin-accepts-valid-permissions
  (let [profile (th/create-profile* 1)
        data    {::th/type :add-profile-plugin
                 ::rpc/profile-id (:id profile)
                 :plugin valid-plugin}
        out     (th/command! data)]

    (t/is (nil? (:error out)))
    (t/is (some? (:result out)))

    (let [saved (th/db-get :profile {:id (:id profile)})
          props (profile/decode-row saved)
          plugins (get-in props [:props :plugins])]
      (t/is (= [plugin-id-1] (:ids plugins)))
      (t/is (= valid-plugin (get-in plugins [:data plugin-id-1]))))))

(t/deftest add-profile-plugin-rejects-invalid-permissions
  (let [profile (th/create-profile* 1)
        plugin  (assoc valid-plugin :permissions #{"content:read" "admin:delete"})
        data    {::th/type :add-profile-plugin
                 ::rpc/profile-id (:id profile)
                 :plugin plugin}
        out     (th/command! data)]

    ;; Schema validation catches invalid permissions before custom validation
    (t/is (th/ex-info? (:error out)))
    (t/is (th/ex-of-type? (:error out) :validation))
    (t/is (th/ex-of-code? (:error out) :params-validation))

    (let [saved (th/db-get :profile {:id (:id profile)})
          props (profile/decode-row saved)
          plugins (get-in props [:props :plugins])]
      (t/is (nil? plugins) "No plugins should be persisted when validation fails"))))

(t/deftest add-profile-plugin-updates-existing-plugin
  (let [profile (th/create-profile* 1)
        data1   {::th/type :add-profile-plugin
                 ::rpc/profile-id (:id profile)
                 :plugin valid-plugin}
        _       (th/command! data1)

        updated-plugin (assoc valid-plugin :name "Updated Plugin")
        data2   {::th/type :add-profile-plugin
                 ::rpc/profile-id (:id profile)
                 :plugin updated-plugin}
        out     (th/command! data2)]

    (t/is (nil? (:error out)))

    (let [saved (th/db-get :profile {:id (:id profile)})
          props (profile/decode-row saved)
          plugins (get-in props [:props :plugins])]
      (t/is (= 1 (count (:ids plugins))) "Should still have only one plugin")
      (t/is (= "Updated Plugin" (get-in plugins [:data plugin-id-1 :name]))))))

(t/deftest remove-profile-plugin-removes-plugin
  (let [profile (th/create-profile* 1)
        data1   {::th/type :add-profile-plugin
                 ::rpc/profile-id (:id profile)
                 :plugin valid-plugin}
        _       (th/command! data1)

        data2   {::th/type :remove-profile-plugin
                 ::rpc/profile-id (:id profile)
                 :plugin-id (uuid/uuid plugin-id-1)}
        out     (th/command! data2)]

    (t/is (nil? (:error out)))

    (let [saved (th/db-get :profile {:id (:id profile)})
          props (profile/decode-row saved)
          plugins (get-in props [:props :plugins])]
      (t/is (= [] (:ids plugins)))
      (t/is (empty? (:data plugins))))))

(t/deftest remove-profile-plugin-handles-nonexistent-plugin
  (let [profile (th/create-profile* 1)
        data    {::th/type :remove-profile-plugin
                 ::rpc/profile-id (:id profile)
                 :plugin-id (uuid/next)}
        out     (th/command! data)]

    (t/is (nil? (:error out)))

    (let [saved (th/db-get :profile {:id (:id profile)})
          props (profile/decode-row saved)
          plugins (get-in props [:props :plugins])]
      (t/is (or (nil? plugins)
                (and (empty? (:ids plugins))
                     (empty? (:data plugins))))
            "Plugins should be nil or empty when no plugins exist"))))

(t/deftest add-profile-plugin-multiple-plugins
  (let [profile (th/create-profile* 1)
        plugin1 valid-plugin
        plugin2 (assoc valid-plugin
                       :plugin-id plugin-id-2
                       :name "Second Plugin")

        data1   {::th/type :add-profile-plugin
                 ::rpc/profile-id (:id profile)
                 :plugin plugin1}
        _       (th/command! data1)

        data2   {::th/type :add-profile-plugin
                 ::rpc/profile-id (:id profile)
                 :plugin plugin2}
        _       (th/command! data2)]

    (let [saved (th/db-get :profile {:id (:id profile)})
          props (profile/decode-row saved)
          plugins (get-in props [:props :plugins])]
      (t/is (= 2 (count (:ids plugins))))
      (t/is (contains? (set (:ids plugins)) plugin-id-1))
      (t/is (contains? (set (:ids plugins)) plugin-id-2))
      (t/is (= "Test Plugin" (get-in plugins [:data plugin-id-1 :name])))
      (t/is (= "Second Plugin" (get-in plugins [:data plugin-id-2 :name]))))))

(t/deftest update-profile-props-rejects-plugins
  (let [profile (th/create-profile* 1)
        data    {::th/type :update-profile-props
                 ::rpc/profile-id (:id profile)
                 :props {:plugins {:ids ["test"] :data {"test" valid-plugin}}}}
        out     (th/command! data)]

    (t/is (th/ex-info? (:error out)))
    (t/is (th/ex-of-type? (:error out) :validation))
    (t/is (th/ex-of-code? (:error out) :params-validation))

    (let [saved (th/db-get :profile {:id (:id profile)})
          props (profile/decode-row saved)]
      (t/is (nil? (get-in props [:props :plugins]))
            ":plugins must not be writable via update-profile-props"))))
