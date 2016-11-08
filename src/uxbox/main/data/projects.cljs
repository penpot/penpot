;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.data.projects
  (:require [cuerdas.core :as str]
            [beicon.core :as rx]
            [uxbox.util.rstore :as rs]
            [uxbox.util.router :as r]
            [uxbox.main.state :as st]
            [uxbox.main.repo :as rp]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.schema :as sc]
            [uxbox.main.data.pages :as udp]))

;; --- Helpers

(defn assoc-project
  "A reduce function for assoc the project
  to the state map."
  [state proj]
  (let [id (:id proj)]
    (update-in state [:projects id] merge proj)))

(defn dissoc-project
  "A reduce function for dissoc the project
  from the state map."
  [state id]
  (update-in state [:projects] dissoc id))

;; --- Initialize

(declare fetch-projects)
(declare projects-fetched?)

(defrecord Initialize []
  rs/UpdateEvent
  (-apply-update [_ state]
    (assoc-in state [:dashboard :section] :dashboard/projects))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (rx/of (fetch-projects))))

(defn initialize
  []
  (Initialize.))

;; --- Projects Fetched

(defrecord ProjectsFetched [projects]
  rs/UpdateEvent
  (-apply-update [_ state]
    (reduce assoc-project state projects)))

(defn projects-fetched
  [projects]
  (ProjectsFetched. projects))

(defn projects-fetched?
  [v]
  (instance? ProjectsFetched v))

;; --- Fetch Projects

(defrecord FetchProjects []
  rs/WatchEvent
  (-apply-watch [_ state s]
    (->> (rp/req :fetch/projects)
         (rx/map :payload)
         (rx/map projects-fetched))))

(defn fetch-projects
  []
  (FetchProjects.))

;; --- Project Created

(defrecord ProjectCreated [project]
  rs/UpdateEvent
  (-apply-update [_ state]
    (assoc-project state project)))

(defn project-created
  [data]
  (ProjectCreated. data))

;; --- Create Project

(defrecord CreateProject [name width height layout]
  rs/WatchEvent
  (-apply-watch [this state s]
    (letfn [(on-success [{project :payload}]
              (rx/of
               (project-created project)
               (udp/create-page {:metadata {:width width
                                            :height height
                                            :layout layout}
                                 :project (:id project)
                                 :name "Page 1"
                                 :data nil})))]
      (->> (rp/req :create/project {:name name})
           (rx/mapcat on-success)))))

(def ^:private create-project-schema
  {:name [sc/required sc/string]
   :width [sc/required sc/integer]
   :height [sc/required sc/integer]
   :layout [sc/required sc/string]})

(defn create-project
  [params]
  (-> (sc/validate! params create-project-schema)
      (map->CreateProject)))

;; --- Delete Project (by id)

(defrecord DeleteProject [id]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (letfn [(on-success [_]
              #(dissoc-project % id))]
      (->> (rp/req :delete/project id)
           (rx/map on-success)))))

(defn delete-project
  [id]
  (if (map? id)
    (DeleteProject. (:id id))
    (DeleteProject. id)))

;; --- Go To & Go To Page

(defrecord GoTo [projectid]
  rs/EffectEvent
  (-apply-effect [_ state]
    (reset! st/loader true))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (letfn [(navigate [pages]
              (let [pageid (:id (first pages))
                    params {:project (str projectid)
                            :page (str pageid)}]
                (r/navigate :workspace/page params)))]
      (rx/merge
       (rx/of #(assoc % :loader true)
              (udp/fetch-pages projectid))
       (->> (rx/filter udp/pages-fetched? s)
            (rx/take 1)
            (rx/map :pages)
            (rx/do #(reset! st/loader false))
            (rx/map navigate))))))

(defrecord GoToPage [projectid pageid]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (let [params {:project (str projectid)
                  :page (str pageid)}]
      (rx/of (r/navigate :workspace/page params)))))

(defn go-to
  "A shortcut event that redirects the user to the
  first page of the project."
  ([projectid] (GoTo. projectid))
  ([projectid pageid] (GoToPage. projectid pageid)))

;; --- Update Opts (Filtering & Ordering)

(defrecord UpdateOpts [order filter]
  rs/UpdateEvent
  (-apply-update [_ state]
    (update-in state [:dashboard :projects] merge
               (when order {:order order})
               (when filter {:filter filter}))))

(defn update-opts
  [& {:keys [order filter] :as opts}]
  (UpdateOpts. order filter))
