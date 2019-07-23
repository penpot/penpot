;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.fixtures
  "A initial fixtures."
  (:require
   [buddy.core.codecs :as codecs]
   [buddy.hashers :as hashers]
   [clj-uuid :as uuid]
   [mount.core :as mount]
   [suricatta.core :as sc]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.media :as media]
   [uxbox.migrations]
   [uxbox.services.pages :as spag]
   [uxbox.services.projects :as sproj]
   [uxbox.services.users :as susers]
   [uxbox.util.transit :as t]))

(defn- mk-uuid
  [prefix i]
  (uuid/v5 uuid/+namespace-oid+ (str prefix i)))

(defn- data-encode
  [data]
  (-> (t/encode data)
      (codecs/bytes->str)))

(defn- create-user
  [conn i]
  (println "create user" i)
  (susers/create-user conn
                      {:username (str "user" i)
                       :id (mk-uuid "user" i)
                       :fullname (str "User " i)
                       :metadata {}
                       :password "123123"
                       :email (str "user" i ".test@uxbox.io")}))

(defn- create-project
  [conn i ui]
  ;; (Thread/sleep 20)
  (println "create project" i "for user" ui)
  (sproj/create-project conn
                        {:id (mk-uuid "project" i)
                         :user (mk-uuid "user" ui)
                         :name (str "project " i)}))

(defn- create-page
  [conn i pi ui]
  ;; (Thread/sleep 1)
  (println "create page" i "for user" ui "for project" pi)
  (spag/create-page conn
                    {:id (mk-uuid "page" i)
                     :user (mk-uuid "user" ui)
                     :project (mk-uuid "project" pi)
                     :data nil
                     :metadata {:width 1024
                                :height 768
                                :layout "tablet"}
                     :name (str "page " i)}))

(def num-users 50)
(def num-projects 5)
(def num-pages 5)

(defn -main
  [& args]
  (-> (mount/only #{#'uxbox.config/config
                    #'uxbox.config/secret
                    #'uxbox.db/datasource
                    #'uxbox.migrations/migrations
                    #'uxbox.media/assets-storage
                    #'uxbox.media/media-storage
                    #'uxbox.media/images-storage
                    #'uxbox.media/thumbnails-storage})
      (mount/start))
  (with-open [conn (db/connection)]
    (sc/atomic conn
      (doseq [i (range num-users)]
        (create-user conn i))

      (doseq [ui (range num-users)]
        (doseq [i (range num-projects)]
          (create-project conn (str ui i) ui)))

      (doseq [pi (range num-projects)]
        (doseq [ui (range num-users)]
          (doseq [i (range num-pages)]
            (create-page conn (str pi ui i) (str ui pi) ui))))))
  (mount/stop))
