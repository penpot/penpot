;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-plugins-test
  (:require
   [app.common.schema :as sm]
   [app.common.types.plugins :as ctp]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
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

(t/deftest add-profile-plugin-rejects-oversized-code
  ;; The merged props must not exceed :profile-props-max-size
  (let [profile (th/create-profile* 1)
        plugin  (assoc valid-plugin :code (apply str (repeat 200 "x")))
        data    {::th/type :add-profile-plugin
                 ::rpc/profile-id (:id profile)
                 :plugin plugin}]
    (with-redefs [cf/get (th/config-get-mock {:profile-props-max-size 100})]
      (let [out (th/command! data)]
        (t/is (th/ex-info? (:error out)))
        (t/is (th/ex-of-type? (:error out) :validation))
        (t/is (th/ex-of-code? (:error out) :props-too-large))))))

(t/deftest add-profile-plugin-rejects-oversized-code-path
  ;; :code holds a manifest path, not content: overlong values are
  ;; rejected by the entry schema before the props size check runs
  (let [profile (th/create-profile* 1)
        plugin  (assoc valid-plugin :code (apply str (repeat 501 "x")))
        data    {::th/type :add-profile-plugin
                 ::rpc/profile-id (:id profile)
                 :plugin plugin}
        out     (th/command! data)]
    (t/is (th/ex-info? (:error out)))
    (t/is (th/ex-of-type? (:error out) :validation))
    (t/is (th/ex-of-code? (:error out) :params-validation))))

(t/deftest remove-profile-plugin-allowed-on-oversized-profile
  ;; Removal shrinks props, so it passes even under a tight limit
  (let [profile (th/create-profile* 1)
        plugin  (assoc valid-plugin :code (apply str (repeat 200 "x")))]
    ;; Seed an oversized registry while the limit is high
    (with-redefs [cf/get (th/config-get-mock {:profile-props-max-size 100000})]
      (let [out (th/command! {::th/type :add-profile-plugin
                              ::rpc/profile-id (:id profile)
                              :plugin plugin})]
        (t/is (nil? (:error out)))))
    ;; Removal under a tighter limit still passes: the seeded registry
    ;; is oversized against it, but the remaining props fit
    (with-redefs [cf/get (th/config-get-mock {:profile-props-max-size 300})]
      (let [out (th/command! {::th/type :remove-profile-plugin
                              ::rpc/profile-id (:id profile)
                              :plugin-id (uuid/uuid plugin-id-1)})]
        (t/is (nil? (:error out)))))))

(t/deftest add-profile-plugin-rejects-51st-plugin
  ;; The registry holds at most 50 plugins; the 51st (new id) must fail
  (let [profile (th/create-profile* 1)]
    ;; Seed 50 plugins
    (doseq [i (range 50)]
      (let [plugin (assoc valid-plugin
                          :plugin-id (str (uuid/next))
                          :name (str "Plugin " i))
            out    (th/command! {::th/type :add-profile-plugin
                                 ::rpc/profile-id (:id profile)
                                 :plugin plugin})]
        (t/is (nil? (:error out)) (str "seed plugin " i " should install"))))
    ;; The 51st must fail with a specific error
    (let [extra (assoc valid-plugin
                       :plugin-id (str (uuid/next))
                       :name "One Too Many")
          out   (th/command! {::th/type :add-profile-plugin
                              ::rpc/profile-id (:id profile)
                              :plugin extra})]
      (t/is (th/ex-info? (:error out)))
      (t/is (th/ex-of-type? (:error out) :validation))
      (t/is (th/ex-of-code? (:error out) :too-many-plugins)))
    ;; And nothing extra was persisted
    (let [saved (th/db-get :profile {:id (:id profile)})
          props (profile/decode-row saved)]
      (t/is (= 50 (count (get-in props [:props :plugins :ids])))))))

(t/deftest add-profile-plugin-updates-existing-at-limit
  ;; Re-adding an existing id at the limit is an update, not a new entry
  (let [profile (th/create-profile* 1)
        ids     (mapv (fn [_] (str (uuid/next))) (range 50))]
    (doseq [[i pid] (map-indexed vector ids)]
      (th/command! {::th/type :add-profile-plugin
                    ::rpc/profile-id (:id profile)
                    :plugin (assoc valid-plugin :plugin-id pid :name (str "Plugin " i))}))
    (let [out (th/command! {::th/type :add-profile-plugin
                            ::rpc/profile-id (:id profile)
                            :plugin (assoc valid-plugin :plugin-id (first ids) :name "Renamed")})]
      (t/is (nil? (:error out)))
      (let [saved (th/db-get :profile {:id (:id profile)})
            props (profile/decode-row saved)]
        (t/is (= 50 (count (get-in props [:props :plugins :ids]))))
        (t/is (= "Renamed" (get-in props [:props :plugins :data (first ids) :name])))))))

(t/deftest add-profile-plugin-full-registry-reports-too-many-before-size
  ;; A full registry plus oversized content reports the count guard,
  ;; which runs before the size check
  (let [profile (th/create-profile* 1)]
    ;; Seed 50 plugins under a generous limit
    (with-redefs [cf/get (th/config-get-mock {:profile-props-max-size 1000000})]
      (doseq [i (range 50)]
        (let [plugin (assoc valid-plugin
                            :plugin-id (str (uuid/next))
                            :name (str "Plugin " i))
              out    (th/command! {::th/type :add-profile-plugin
                                   ::rpc/profile-id (:id profile)
                                   :plugin plugin})]
          (t/is (nil? (:error out)) (str "seed plugin " i " should install")))))
    ;; Tight limit + 51st small plugin: count wins over size
    (with-redefs [cf/get (th/config-get-mock {:profile-props-max-size 100})]
      (let [extra (assoc valid-plugin
                         :plugin-id (str (uuid/next))
                         :name "One Too Many")
            out   (th/command! {::th/type :add-profile-plugin
                                ::rpc/profile-id (:id profile)
                                :plugin extra})]
        (t/is (th/ex-info? (:error out)))
        (t/is (th/ex-of-type? (:error out) :validation))
        (t/is (th/ex-of-code? (:error out) :too-many-plugins))))
    ;; And nothing extra was persisted
    (let [saved (th/db-get :profile {:id (:id profile)})
          props (profile/decode-row saved)]
      (t/is (= 50 (count (get-in props [:props :plugins :ids])))))))

(t/deftest remove-profile-plugin-noop-on-oversized-profile-without-plugins
  ;; Removing an absent id changes nothing: no write, no size failure,
  ;; and no :plugins key is manufactured
  (let [profile (th/create-profile* 1)
        big     {:onboarding-questions {:big-blob (apply str (repeat 200 "x"))}}]
    (th/db-update! :profile {:props (db/tjson big)} {:id (:id profile)})
    (with-redefs [cf/get (th/config-get-mock {:profile-props-max-size 100})]
      (let [out (th/command! {::th/type :remove-profile-plugin
                              ::rpc/profile-id (:id profile)
                              :plugin-id (uuid/next)})]
        (t/is (nil? (:error out)))))
    (let [saved (th/db-get :profile {:id (:id profile)})
          props (profile/decode-row saved)]
      (t/is (nil? (get-in props [:props :plugins])))
      (t/is (= big (get props :props))))))

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

;; --- Stored registries over the caps

(defn- legacy-entry
  [plugin-id & {:as attrs}]
  (merge valid-plugin {:plugin-id plugin-id :code "plugin.js"} attrs))

(defn- legacy-registry
  [entries]
  {:ids  (mapv :plugin-id entries)
   :data (into {} (map (juxt :plugin-id identity)) entries)})

(defn- stored-props
  "Props as stored in the database, without the read-time clamping."
  [profile-id]
  (db/decode-transit-pgobject (:props (th/db-get :profile {:id profile-id}))))

(t/deftest get-profile-clamps-legacy-registry
  (let [profile (th/create-profile* 1)
        big     (apply str (repeat 10000 "x"))
        ;; "😀" is two chars, so char 500 is a high surrogate
        emoji   (str (apply str (repeat 499 "n")) "😀")
        entries (into [(legacy-entry plugin-id-1
                                     :name emoji
                                     :description big
                                     :host big
                                     :code big
                                     :icon big)]
                      (map #(legacy-entry (str "extra-" %)))
                      (range 60))
        stored  (-> (legacy-registry entries)
                    (update :ids conj "dangling"))]
    (th/db-update! :profile {:props (db/tjson {:plugins stored :renderer :wasm})}
                   {:id (:id profile)})
    (let [out     (th/command! {::th/type :get-profile ::rpc/profile-id (:id profile)})
          props   (get-in out [:result :props])
          plugins (:plugins props)
          entry   (get-in plugins [:data plugin-id-1])]
      (t/is (nil? (:error out)))
      (t/is (= :wasm (:renderer props)) "other props are kept")
      (t/is (= (mapv :plugin-id (take ctp/max-plugins entries)) (:ids plugins))
            "keeps the first plugins in registry order, drops ids without data")
      (t/is (= (set (:ids plugins)) (set (keys (:data plugins)))))
      (t/is (= (apply str (repeat 499 "n")) (:name entry)) "does not split a surrogate pair")
      (doseq [k [:description :host :code :icon]]
        (t/is (= (get ctp/registry-entry-max-lengths k) (count (get entry k)))
              (str k " truncated to its cap")))
      (t/is (sm/validate ctp/schema:plugin-registry plugins)))
    (t/is (= stored (:plugins (stored-props (:id profile))))
          "reading does not write")))

(t/deftest get-profile-keeps-valid-registry
  (let [profile (th/create-profile* 1)
        stored  (legacy-registry [(legacy-entry plugin-id-1)])]
    (th/db-update! :profile {:props (db/tjson {:plugins stored})} {:id (:id profile)})
    (let [out (th/command! {::th/type :get-profile ::rpc/profile-id (:id profile)})]
      (t/is (= stored (get-in out [:result :props :plugins]))))))

(t/deftest oversized-legacy-profile-recovers-on-write
  ;; A stored registry over the caps pushes props past the size limit;
  ;; the clamped read lets writes through and saves the clamped registry
  (let [profile (th/create-profile* 1)
        code    (apply str (repeat 3000 "c"))
        entries [(legacy-entry plugin-id-1 :code code)
                 (legacy-entry plugin-id-2 :code code)]]
    (th/db-update! :profile {:props (db/tjson {:plugins (legacy-registry entries)})}
                   {:id (:id profile)})
    (with-redefs [cf/get (th/config-get-mock {:profile-props-max-size 5000})]
      (t/is (thrown? Exception (profile/check-props-size (stored-props (:id profile))))
            "the stored props exceed the limit")
      (let [out (th/command! {::th/type :update-profile-props
                              ::rpc/profile-id (:id profile)
                              :props {:workspace-visited true}})]
        (t/is (nil? (:error out))))
      (let [out (th/command! {::th/type :remove-profile-plugin
                              ::rpc/profile-id (:id profile)
                              :plugin-id (uuid/uuid plugin-id-1)})]
        (t/is (nil? (:error out)))))
    (let [props (stored-props (:id profile))]
      (t/is (true? (:workspace-visited props)))
      (t/is (= [plugin-id-2] (get-in props [:plugins :ids])))
      (t/is (= 500 (count (get-in props [:plugins :data plugin-id-2 :code])))
            "the write saved the clamped registry"))))
