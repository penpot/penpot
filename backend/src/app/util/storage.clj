;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.util.storage
  "A local filesystem storage implementation."
  (:require
   [app.common.exceptions :as ex]
   [buddy.core.codecs :as bc]
   [buddy.core.nonce :as bn]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [datoteka.core :as fs])
  (:import
   java.io.ByteArrayInputStream
   java.io.InputStream
   java.io.OutputStream
   java.net.URI
   java.nio.file.NoSuchFileException
   java.nio.file.Path))

(defn uri
  [v]
  (cond
    (instance? URI v) v
    (string? v) (URI. v)
    :else (throw (IllegalArgumentException. "unexpected input"))))

(defn- normalize-path
  [^Path base ^Path path]
  (when (fs/absolute? path)
    (ex/raise :type :filesystem-error
              :code :suspicious-operation
              :hint "Suspicios operation: absolute path not allowed."
              :contex {:path path :base base}))
  (let [^Path fullpath (.resolve base path)
        ^Path fullpath (.normalize fullpath)]
    (when-not (.startsWith fullpath base)
      (ex/raise :type :filesystem-error
                :code :suspicious-operation
                :hint "Suspicios operation: go to parent dir is not allowed."
                :contex {:path path :base base}))
    fullpath))

(defn- transform-path
  [storage ^Path path]
  (if-let [xf (::xf storage)]
    ((xf (fn [_ b] b)) nil path)
    path))

(defn blob
  [^String v]
  (let [data (.getBytes v "UTF-8")]
    (ByteArrayInputStream. ^bytes data)))

(defn save!
  [storage path content]
  (s/assert ::storage storage)
  (let [^Path base (::base-path storage)
        ^Path path (->> (fs/path path)
                        (transform-path storage))
        ^Path fullpath (normalize-path base path)]
    (when-not (fs/exists? (.getParent fullpath))
      (fs/create-dir (.getParent fullpath)))
    (loop [iteration nil]
      (let [[basepath ext] (fs/split-ext fullpath)
            candidate (fs/path (str basepath iteration ext))]
        (if (fs/exists? candidate)
          (recur (if (nil? iteration) 1 (inc iteration)))
          (with-open [^InputStream src (io/input-stream content)
                      ^OutputStream dst (io/output-stream candidate)]
            (io/copy src dst)
            (fs/relativize candidate base)))))))

(defn delete!
  [storage path]
  (s/assert ::storage storage)
  (try
    (->> (fs/path path)
         (normalize-path (::base-path storage))
         (fs/delete))
    true
    (catch NoSuchFileException _e
      false)))

(defn clear!
  [storage]
  (s/assert ::storage storage)
  (fs/delete (::base-path storage))
  (fs/create-dir (::base-path storage))
  nil)

(defn exists?
  [storage path]
  (s/assert ::storage storage)
  (->> (fs/path path)
       (normalize-path (::base-path storage))
       (fs/exists?)))

(defn lookup
  [storage path]
  (s/assert ::storage storage)
  (->> (fs/path path)
       (normalize-path (::base-path storage))))

(defn public-uri
  [storage path]
  (s/assert ::storage storage)
  (let [^URI base (::base-uri storage)
        ^String path (str path)]
    (.resolve base path)))

(s/def ::base-path (s/or :path fs/path? :str string?))
(s/def ::base-uri (s/or :uri #(instance? URI %) :str string?))
(s/def ::xf fn?)

(s/def ::storage
  (s/keys :req [::base-path] :opt [::xf ::base-uri]))

(s/def ::create-options
  (s/keys :req-un [::base-path] :opt-un [::xf ::base-uri]))

(defn create
  "Create an instance of local FileSystem storage providing an
  absolute base path.

  If that path does not exists it will be automatically created,
  if it exists but is not a directory, an exception will be
  raised.

  This function expects a map with the following options:
  - `:base-path`: a fisical directory on your local machine
  - `:base-uri`: a base uri used for resolve the files
  "
  [{:keys [base-path base-uri xf] :as options}]
  (s/assert ::create-options options)
  (let [^Path base-path (fs/path base-path)]
    (when (and (fs/exists? base-path)
               (not (fs/directory? base-path)))
      (ex/raise :type :filesystem-error
                :code :file-already-exists
                :hint "File already exists, expects directory."))
    (when-not (fs/exists? base-path)
      (fs/create-dir base-path))
    (cond-> {::base-path base-path}
      base-uri (assoc ::base-uri (uri base-uri))
      xf (assoc ::xf xf))))

;; This is don't need to be secure and we dont need to reseed it; the
;; security guarranties of this prng instance are very low (we only
;; use it for generate a random path where store the file).

(def ^:private prng
  (delay
    (doto (java.security.SecureRandom/getInstance "SHA1PRNG")
      (.setSeed ^bytes (bn/random-bytes 64)))))

(defn with-xf
  [storage xfm]
  (let [xf (::xf storage)]
    (if (nil? xf)
      (assoc storage ::xf xfm)
      (assoc storage ::xf (comp xf xfm)))))

(def random-path
  (map (fn [^Path path]
         (let [name (str (.getFileName path))
               hash (-> (bn/random-bytes 10 @prng)
                        (bc/bytes->b64u)
                        (bc/bytes->str))
               tokens (re-seq #"[\w\d\-\_]{2}" hash)
               path-tokens (take 3 tokens)
               rest-tokens (drop 3 tokens)
               path (fs/path path-tokens)
               frest (apply str rest-tokens)]
           (fs/path (list path frest name))))))

(def slugify-filename
  (map (fn [path]
         (let [parent (or (fs/parent path) "")
               [name ext] (fs/split-ext (fs/name path))]
           (fs/path parent (str (str/uslug name) ext))))))

(defn prefix-path
  [prefix]
  (map (fn [^Path path] (fs/join (fs/path prefix) path))))
