;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.file
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.record :as crc]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.exports.files :as exports.files]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.versions :as dwv]
   [app.main.features :as features]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.worker :as uw]
   [app.plugins.page :as page]
   [app.plugins.parser :as parser]
   [app.plugins.register :as r]
   [app.plugins.user :as user]
   [app.plugins.utils :as u]
   [app.util.http :as http]
   [app.util.object :as obj]
   [app.util.time :as dt]
   [beicon.v2.core :as rx]))

(declare file-version-proxy)

(deftype FileVersionProxy [$plugin $file $version $data]
  Object
  (restore
    [_]
    (cond
      (not (r/check-permission $plugin "content:write"))
      (u/display-not-valid :restore "Plugin doesn't have 'content:write' permission")

      :else
      (let [project-id (:current-project-id @st/state)]
        (st/emit! (dwv/restore-version project-id $file $version :plugin)))))

  (remove
    [_]
    (js/Promise.
     (fn [resolve reject]
       (cond
         (not (r/check-permission $plugin "content:write"))
         (u/reject-not-valid reject :remove "Plugin doesn't have 'content:write' permission")

         :else
         (->> (rp/cmd! :delete-file-snapshot {:id $version})
              (rx/subs! #(resolve) reject))))))

  (pin
    [_]
    (js/Promise.
     (fn [resolve reject]
       (cond
         (not (r/check-permission $plugin "content:write"))
         (u/reject-not-valid reject :pin "Plugin doesn't have 'content:write' permission")

         (not= "system" (:created-by $data))
         (u/reject-not-valid reject :pin "Only auto-saved versions can be pinned")

         :else
         (let [params  {:id $version
                        :label (dt/format (:created-at $data) :date-full)}]
           (->> (rx/zip (rp/cmd! :get-team-users {:file-id $file})
                        (rp/cmd! :update-file-snapshot params))
                (rx/subs! (fn [[users data]]
                            (let [users (d/index-by :id users)]
                              (resolve (file-version-proxy $plugin $file users data))))
                          reject))))))))

(defn file-version-proxy
  [plugin-id file-id users data]
  (let [data (atom data)]
    (crc/add-properties!
     (FileVersionProxy. plugin-id file-id (:id @data) data)
     {:name "$plugin" :enumerable false :get (constantly plugin-id)}
     {:name "$file" :enumerable false :get (constantly file-id)}
     {:name "$version" :enumerable false :get (constantly (:id @data))}
     {:name "$data" :enumerable false :get (constantly @data)}

     {:name "label"
      :get (fn [_] (:label @data))
      :set
      (fn [_ value]
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

     {:name "createdBy"
      :get (fn [_]
             (when-let [user-data (get users (:profile-id @data))]
               (user/user-proxy plugin-id user-data)))}

     {:name "createdAt"
      :get (fn [_]
             (.toJSDate ^js (:created-at @data)))}

     {:name "isAutosave"
      :get (fn [_]
             (= "system" (:created-by @data)))})))

(deftype FileProxy [$plugin $id]
  Object
  (getPages [_]
    (let [file (u/locate-file $id)]
      (apply array (sequence (map #(page/page-proxy $plugin $id %)) (dm/get-in file [:data :pages])))))

  ;; Plugin data
  (getPluginData
    [self key]
    (cond
      (not (string? key))
      (u/display-not-valid :getPluginData-key key)

      :else
      (let [file (u/proxy->file self)]
        (dm/get-in file [:data :plugin-data (keyword "plugin" (str $plugin)) key]))))

  (setPluginData
    [_ key value]
    (cond
      (or (not (string? key)) (empty? key))
      (u/display-not-valid :setPluginData-key key)

      (and (some? value) (not (string? value)))
      (u/display-not-valid :setPluginData-value value)

      (not (r/check-permission $plugin "content:write"))
      (u/display-not-valid :setPluginData "Plugin doesn't have 'content:write' permission")

      :else
      (st/emit! (dw/set-plugin-data $id :file (keyword "plugin" (str $plugin)) key value))))

  (getPluginDataKeys
    [self]
    (let [file (u/proxy->file self)]
      (apply array (keys (dm/get-in file [:data :plugin-data (keyword "plugin" (str $plugin))])))))

  (getSharedPluginData
    [self namespace key]
    (cond
      (not (string? namespace))
      (u/display-not-valid :getSharedPluginData-namespace namespace)

      (not (string? key))
      (u/display-not-valid :getSharedPluginData-key key)

      :else
      (let [file (u/proxy->file self)]
        (dm/get-in file [:data :plugin-data (keyword "shared" namespace) key]))))

  (setSharedPluginData
    [_ namespace key value]

    (cond
      (or (not (string? namespace)) (empty? namespace))
      (u/display-not-valid :setSharedPluginData-namespace namespace)

      (or (not (string? key)) (empty? key))
      (u/display-not-valid :setSharedPluginData-key key)

      (and (some? value) (not (string? value)))
      (u/display-not-valid :setSharedPluginData-value value)

      (not (r/check-permission $plugin "content:write"))
      (u/display-not-valid :setSharedPluginData "Plugin doesn't have 'content:write' permission")

      :else
      (st/emit! (dw/set-plugin-data $id :file (keyword "shared" namespace) key value))))

  (getSharedPluginDataKeys
    [self namespace]
    (cond
      (not (string? namespace))
      (u/display-not-valid :getSharedPluginDataKeys namespace)

      :else
      (let [file (u/proxy->file self)]
        (apply array (keys (dm/get-in file [:data :plugin-data (keyword "shared" namespace)]))))))

  (createPage
    [_]
    (cond
      (not (r/check-permission $plugin "content:write"))
      (u/display-not-valid :createPage "Plugin doesn't have 'content:write' permission")

      :else
      (let [page-id (uuid/next)]
        (st/emit! (dw/create-page {:page-id page-id :file-id $id}))
        (page/page-proxy $plugin $id page-id))))

  (export
    [self format type]
    (let [type (or (parser/parse-keyword type) :all)]
      (cond
        (not (contains? #{"penpot" "zip"} format))
        (u/display-not-valid :format type)

        (not (contains? (set exports.files/valid-types) type))
        (u/display-not-valid :type type)

        :else
        (let [file       (u/proxy->file self)
              features   (features/get-team-enabled-features @st/state)
              team-id    (:current-team-id @st/state)
              format     (case format
                           "penpot" (if (contains? cf/flags :export-file-v3)
                                      :binfile-v3
                                      :binfile-v1)
                           "zip"    :legacy-zip)]
          (js/Promise.
           (fn [resolve reject]
             (->> (uw/ask-many!
                   {:cmd :export-files
                    :format format
                    :type type
                    :team-id team-id
                    :features features
                    :files [file]})
                  (rx/mapcat
                   (fn [msg]
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
                  (rx/map (fn [data] (js/Uint8Array. data)))
                  (rx/subs! resolve reject))))))))


  (findVersions
    [_ criteria]
    (let [user (obj/get criteria "createdBy" nil)]
      (js/Promise.
       (fn [resolve reject]
         (cond
           (not (r/check-permission $plugin "content:read"))
           (u/reject-not-valid reject :findVersions "Plugin doesn't have 'content:read' permission")

           (and (not user) (not (user/user-proxy? user)))
           (u/reject-not-valid reject :findVersions-user "Created by user is not a valid user object")

           :else
           (->> (rx/zip (rp/cmd! :get-team-users {:file-id $id})
                        (rp/cmd! :get-file-snapshots {:file-id $id}))
                (rx/take 1)
                (rx/subs!
                 (fn [[users snapshots]]
                   (let [users (d/index-by :id users)]
                     (->> snapshots
                          (filter #(= (dm/str (:profile-id %)) (obj/get user "id")))
                          (map #(file-version-proxy $plugin $id users %))
                          (sequence)
                          (apply array)
                          (resolve))))
                 reject)))))))

  (saveVersion
    [_ label]
    (let [users-promise
          (js/Promise.
           (fn [resolve reject]
             (->> (rp/cmd! :get-team-users {:file-id $id})
                  (rx/subs! resolve reject))))

          create-version-promise
          (js/Promise.
           (fn [resolve reject]
             (cond
               (not (r/check-permission $plugin "content:write"))
               (u/reject-not-valid reject :findVersions "Plugin doesn't have 'content:write' permission")

               :else
               (st/emit! (dwv/create-version-from-plugins $id label resolve reject)))))]
      (-> (js/Promise.all #js [users-promise create-version-promise])
          (.then
           (fn [[users data]]
             (let [users (d/index-by :id users)]
               (file-version-proxy $plugin $id users data))))))))

(crc/define-properties!
  FileProxy
  {:name js/Symbol.toStringTag
   :get (fn [] (str "FileProxy"))})

(defn file-proxy? [p]
  (instance? FileProxy p))

(defn file-proxy
  [plugin-id id]
  (crc/add-properties!
   (FileProxy. plugin-id id)
   {:name "$plugin" :enumerable false :get (constantly plugin-id)}
   {:name "$id" :enumerable false :get (constantly id)}

   {:name "id"
    :get #(dm/str (obj/get % "$id"))}

   {:name "name"
    :get #(-> % u/proxy->file :name)}

   {:name "pages"
    :get #(.getPages ^js %)}))
