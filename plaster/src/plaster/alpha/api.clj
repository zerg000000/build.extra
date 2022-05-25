(ns plaster.alpha.api
  "Extra tasks for create flat folder bundle"
  (:require
    [babashka.fs :as fs]
    [clojure.tools.build.api :as b]))


(defn copy-libs
  [{:keys [basis lib-dir class-dir conflict-handler ignores]
    :or {conflict-handler println}}]
  (reduce-kv
    (fn [state lib {:keys [paths]}]
      (when (some state paths)
        (conflict-handler state lib paths))
      (doseq [path paths]
        (let [f (fs/file path)]
          (cond
            (fs/regular-file? f) (b/copy-file {:src path :target (str lib-dir "/" (fs/file-name f))})
            (fs/directory? f) (b/copy-dir {:src-dirs [path]
                                           :ignores ignores
                                           :target-dir class-dir})
            :else (prn path))))
      (apply conj state paths))
    #{}
    (:libs basis)))
