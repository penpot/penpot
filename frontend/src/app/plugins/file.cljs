;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.file
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.exports.files :as exports.files]
   [app.main.data.plugins :as dp]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.versions :as dwv]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.worker :as mw]
   [app.plugins.format :as format]
   [app.plugins.page :as page]
   [app.plugins.parser :as parser]
   [app.plugins.register :as r]
   [app.plugins.user :as user]
   [app.plugins.utils :as u]
   [app.util.http :as http]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]))

(defn file-version-proxy?
  [proxy]
  (obj/type-of? proxy "FileVersionProxy"))

(defn file-version-proxy
  [plugin-id file-id users data]
  (let [data (atom data)]
    (obj/reify {:name "FileVersionProxy"}
      :$plugin {:enumerable false :get (fn [] plugin-id)}
      :$file   {:enumerable false :get (fn [] file-id)}

      :label
      {:get #(:label @data)
       :set
       (fn [value]
         (cond
           (not (r/check-permission plugin-id "content:write"))
           (u/display-not-valid :label "Plugin doesn't have 'content:write' permission")

           (or (not (string? value)) (empty? value))
           (u/display-not-valid :label value)

           :else
           (do (swap! data assoc :label value :created-by "user")
               (->> (rp/cmd! :update-file-snapshot {:id (:id @data) :label value})
                    (rx/take 1)
                    (rx/subs! identity)))))}

      :createdBy
      {:get
       (fn []
         (when-let [user-data (get users (:profile-id @data))]
           (user/user-proxy plugin-id user-data)))}

      :createdAt
      {:get #(.toJSDate ^js (:created-at @data))}

      :isAutosave
      {:get #(= "system" (:created-by @data))}

      :restore
      (fn []
        (js/Promise.
         (fn [resolve reject]
           (cond
             (not (r/check-permission plugin-id "content:write"))
             (u/reject-not-valid reject :restore "Plugin doesn't have 'content:write' permission")

             :else
             (let [version-id (get @data :id)]
               (st/emit! (dwv/restore-version-from-plugin file-id version-id resolve reject)))))))

      :remove
      (fn []
        (js/Promise.
         (fn [resolve reject]
           (cond
             (not (r/check-permission plugin-id "content:write"))
             (u/reject-not-valid reject :remove "Plugin doesn't have 'content:write' permission")

             :else
             (let [version-id (:id @data)]
               (->> (rp/cmd! :delete-file-snapshot {:id version-id})
                    (rx/map (constantly nil))
                    (rx/subs! resolve reject)))))))

      :pin
      (fn []
        (js/Promise.
         (fn [resolve reject]
           (cond
             (not (r/check-permission plugin-id "content:write"))
             (u/reject-not-valid reject :pin "Plugin doesn't have 'content:write' permission")

             (not= "system" (:created-by @data))
             (u/reject-not-valid reject :pin "Only auto-saved versions can be pinned")

             :else
             (let [params  {:id (:id @data)
                            :label (ct/format-inst (:created-at @data) :localized-date)}]
               (->> (rx/zip (rp/cmd! :get-team-users {:file-id file-id})
                            (rp/cmd! :update-file-snapshot params))
                    (rx/subs! (fn [[users data]]
                                (let [users (d/index-by :id users)]
                                  (resolve (file-version-proxy plugin-id file-id users @data))))
                              reject))))))))))

(defn file-proxy? [p]
  (obj/type-of? p "FileProxy"))

(defn file-proxy
  [plugin-id id]

  (obj/reify {:name "FileProxy"}
    :$plugin {:enumerable false :get (fn [] plugin-id)}
    :$id {:enumerable false :get (fn [] id)}

    :id
    {:get #(format/format-id id)}

    :name
    {:get #(-> (u/locate-file id) :name)}

    :pages
    {:this true
     :get #(.getPages ^js %)}

    :getPages
    (fn []
      (let [file (u/locate-file id)]
        (apply array (sequence (map #(page/page-proxy plugin-id id %)) (dm/get-in file [:data :pages])))))

    ;; Plugin data
    :getPluginData
    (fn [key]
      (cond
        (not (string? key))
        (u/display-not-valid :getPluginData-key key)

        :else
        (let [file (u/locate-file id)]
          (dm/get-in file [:data :plugin-data (keyword "plugin" (str plugin-id)) key]))))

    :setPluginData
    (fn [key value]
      (cond
        (or (not (string? key)) (empty? key))
        (u/display-not-valid :setPluginData-key key)

        (not (string? value))
        (u/display-not-valid :setPluginData-value value)

        (not (r/check-permission plugin-id "content:write"))
        (u/display-not-valid :setPluginData "Plugin doesn't have 'content:write' permission")

        :else
        (st/emit! (dp/set-plugin-data id :file (keyword "plugin" (str plugin-id)) key value))))

    :getPluginDataKeys
    (fn []
      (let [file (u/locate-file id)]
        (apply array (keys (dm/get-in file [:data :plugin-data (keyword "plugin" (dm/str plugin-id))])))))

    :getSharedPluginData
    (fn [namespace key]
      (cond
        (not (string? namespace))
        (u/display-not-valid :getSharedPluginData-namespace namespace)

        (not (string? key))
        (u/display-not-valid :getSharedPluginData-key key)

        :else
        (let [file (u/locate-file id)]
          (dm/get-in file [:data :plugin-data (keyword "shared" namespace) key]))))

    :setSharedPluginData
    (fn [namespace key value]
      (cond
        (or (not (string? namespace)) (empty? namespace))
        (u/display-not-valid :setSharedPluginData-namespace namespace)

        (or (not (string? key)) (empty? key))
        (u/display-not-valid :setSharedPluginData-key key)

        (not (string? value))
        (u/display-not-valid :setSharedPluginData-value value)

        (not (r/check-permission plugin-id "content:write"))
        (u/display-not-valid :setSharedPluginData "Plugin doesn't have 'content:write' permission")

        :else
        (st/emit! (dp/set-plugin-data id :file (keyword "shared" namespace) key value))))

    :getSharedPluginDataKeys
    (fn [namespace]
      (cond
        (not (string? namespace))
        (u/display-not-valid :getSharedPluginDataKeys namespace)

        :else
        (let [file (u/locate-file id)]
          (apply array (keys (dm/get-in file [:data :plugin-data (keyword "shared" namespace)]))))))

    :createPage
    (fn []
      (cond
        (not (r/check-permission plugin-id "content:write"))
        (u/display-not-valid :createPage "Plugin doesn't have 'content:write' permission")

        :else
        (let [page-id (uuid/next)]
          (st/emit! (dw/create-page {:page-id page-id :file-id id}))
          (page/page-proxy plugin-id id page-id))))

    :export
    (fn [format type]
      (js/Promise.
       (fn [resolve reject]
         (let [type (or (parser/parse-keyword type) :all)]
           (cond
             (and (some? format) (not (contains? #{"penpot" "zip"} format)))
             (u/reject-not-valid reject :format (dm/str "Invalid format: " format))

             (not (contains? (set exports.files/valid-types) type))
             (u/reject-not-valid reject :format (dm/str "Invalid type: " type))

             :else
             (let [file       (u/locate-file id)
                   features   (:features @st/state)
                   team-id    (:current-team-id @st/state)
                   format     (case format
                                "zip"    :legacy-zip

                                (if (contains? cf/flags :export-file-v3)
                                  :binfile-v3
                                  :binfile-v1))]
               (->> (mw/ask-many!
                     {:cmd :export-files
                      :format format
                      :type type
                      :team-id team-id
                      :features features
                      :files [file]})
                    (rx/mapcat
                     (fn [msg]
                       (.log js/console msg)
                       (case (:type msg)
                         :error
                         (rx/throw (ex-info "cannot export file" {:type :export-file}))

                         :progress
                         (rx/empty)

                         :finish
                         (http/send! {:method :get
                                      :uri (:uri msg)
                                      :mode :no-cors
                                      :response-type :buffer}))))
                    (rx/take 1)
                    (rx/map #(js/Uint8Array. (:body %)))
                    (rx/subs! resolve reject))))))))
    :findVersions
    (fn [criteria]
      (let [user (obj/get criteria "createdBy" nil)]
        (js/Promise.
         (fn [resolve reject]
           (cond
             (not (r/check-permission plugin-id "content:read"))
             (u/reject-not-valid reject :findVersions "Plugin doesn't have 'content:read' permission")

             (and (some? user) (not (user/user-proxy? user)))
             (u/reject-not-valid reject :findVersions-user "Created by user is not a valid user object")

             :else
             (->> (rx/zip (rp/cmd! :get-team-users {:file-id id})
                          (rp/cmd! :get-file-snapshots {:file-id id}))
                  (rx/take 1)
                  (rx/subs!
                   (fn [[users snapshots]]
                     (let [users (d/index-by :id users)]
                       (->> snapshots
                            (filter #(or (not (obj/get user "id"))
                                         (= (dm/str (:profile-id %))
                                            (obj/get user "id"))))
                            (map #(file-version-proxy plugin-id id users %))
                            (sequence)
                            (apply array)
                            (resolve))))
                   reject)))))))

    :saveVersion
    (fn [label]
      (let [users-promise
            (js/Promise.
             (fn [resolve reject]
               (->> (rp/cmd! :get-team-users {:file-id id})
                    (rx/subs! resolve reject))))

            create-version-promise
            (js/Promise.
             (fn [resolve reject]
               (cond
                 (not (r/check-permission plugin-id "content:write"))
                 (u/reject-not-valid reject :findVersions "Plugin doesn't have 'content:write' permission")

                 :else
                 (st/emit! (dwv/create-version-from-plugins id label resolve reject)))))]
        (-> (js/Promise.all #js [users-promise create-version-promise])
            (.then
             (fn [[users data]]
               (let [users (d/index-by :id users)]
                 (file-version-proxy plugin-id id users data)))))))))
