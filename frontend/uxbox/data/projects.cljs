(ns uxbox.data.projects
  (:require [uxbox.rstore :as rs]
            [uxbox.router :as r]
            [uxbox.state :as st]
            [uxbox.schema :as sc]
            [bouncer.validators :as v]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schemas
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static +project-schema+
  {:name [v/required v/string]
   :width [v/required v/integer]
   :height [v/required v/integer]
   :layout [v/required sc/keyword]})

(def ^:static +page-schema+
  {:name [v/required v/string]
   :width [v/required v/integer]
   :height [v/required v/integer]
   :project [v/required sc/uuid]})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-page
  [{:keys [name width height project] :as data}]
  (sc/validate! +page-schema+ data)
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [uuid (random-uuid)
            page {:id uuid
                  :project project
                  :name name
                  :width width
                  :height height}]
        (-> state
            (update-in [:pages] conj uuid)
            (update-in [:projects-by-id project :pages] conj uuid)
            (update-in [:pages-by-id] assoc uuid page))))

    IPrintWithWriter
    (-pr-writer [mv writer _]
      (-write writer "#<event:u.s.p/create-page>"))))

(defn create-project
  [{:keys [name width height layout] :as data}]
  (sc/validate! +project-schema+ data)
  (let [uuid (random-uuid)]
    (reify
      rs/UpdateEvent
      (-apply-update [_ state]
        (let [proj {:id uuid
                    :name name
                    :width width
                    :height height
                    :pages []}]
          (-> state
              (update-in [:projects] conj uuid)
              (update-in [:projects-by-id] assoc uuid proj))))

      rs/EffectEvent
      (-apply-effect [_ state]
        (rs/emit! (create-page {:name "Page 1"
                                :width width
                                :height height
                                :project uuid})))

      IPrintWithWriter
      (-pr-writer [mv writer _]
        (-write writer "#<event:u.s.p/create-project>")))))

(defn initialize-workspace
  [projectid pageid]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc state :workspace {:project projectid :page pageid}))

    IPrintWithWriter
    (-pr-writer [mv writer _]
      (-write writer "#<event:u.s.p/go-to-project>"))))

(defn go-to-project
  "A shortcut event that redirects the user to the
  first page of the project."
  ([projectid]
   (go-to-project projectid nil))
  ([projectid pageid]
   (reify
     rs/EffectEvent
     (-apply-effect [_ state]
       (if pageid
         (rs/emit! (r/navigate :main/page {:project-uuid projectid
                                           :page-uuid pageid}))
         (let [pages (get-in state [:projects-by-id projectid :pages])]
           (rs/emit! (r/navigate :main/page {:project-uuid projectid
                                             :page-uuid (first pages)})))))
     IPrintWithWriter
     (-pr-writer [mv writer _]
       (-write writer "#<event:u.s.p/go-to-project")))))
