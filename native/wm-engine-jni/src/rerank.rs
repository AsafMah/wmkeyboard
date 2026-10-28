//! The context reranker (`wm-engine`'s `rerank`): `PredictionNative.rerank`,
//! which the Kotlin `NgramReranker` calls with the strip's head.

use std::ptr::null_mut;
use std::sync::Arc;

use jni::objects::{JBooleanArray, JClass, JFloatArray, JIntArray, JLongArray, JObjectArray, JString};
use jni::sys::{jdouble, jintArray, jlong};
use jni::JNIEnv;
use memmap2::Mmap;

use wm_engine::rerank::{self, Candidate, Context, Word};
use wm_engine::wmng::Pack;

use crate::dict::{java_utf8, PACKS};
use crate::guard::{give_ints, guarded, object, read_units};
use crate::lexicon::LEXICONS;

fn word(env: &mut JNIEnv, s: &JString) -> Option<Word> {
    let mut units = Vec::new();
    read_units(env, s, &mut units)?;
    let mut bytes = Vec::new();
    java_utf8(&units, &mut bytes);
    Some(Word { key: String::from_utf16_lossy(&units), bytes })
}

fn optional_word(env: &mut JNIEnv, s: &JString) -> Option<Option<Word>> {
    if s.is_null() {
        Some(None)
    } else {
        word(env, s).map(Some)
    }
}

/// `rerank(lexicon, packs, prev, prev2, prev3, skip, candidates, seeds,
/// recent, lm)`: the candidates' new order as indices, or null when none had
/// any evidence. Every word arrives as the key the stores file it under;
/// `seeds[i]` is the bundled count of `prev candidates[i]`, `recent[i]`
/// whether the conversation holds it; `skip` is the word two back when the
/// previous word is one no store knows, else null; `lm[i]` is the context
/// model's ln P(candidate) (NaN where it has none), or null without one;
/// `lmWeight` is the model's rerank weight (the shipped one when 0 or less).
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_rerank(
    mut env: JNIEnv,
    _class: JClass,
    lexicon: jlong,
    packs: JLongArray,
    prev: JString,
    prev2: JString,
    prev3: JString,
    skip: JString,
    candidates: JObjectArray,
    seeds: JIntArray,
    recent: JBooleanArray,
    lm: JFloatArray,
    lm_weight: jdouble,
) -> jintArray {
    guarded(&mut env, null_mut(), |env| {
        let store = object!(env, LEXICONS, lexicon);
        let mut handles = vec![0i64; env.get_array_length(&packs).ok()? as usize];
        env.get_long_array_region(&packs, 0, &mut handles).ok()?;
        let mut held: Vec<Arc<Pack<Mmap>>> = Vec::with_capacity(handles.len());
        for &handle in &handles {
            held.push(object!(env, PACKS, handle));
        }
        let prev = word(env, &prev)?;
        let prev2 = optional_word(env, &prev2)?;
        let prev3 = optional_word(env, &prev3)?;
        let skip = optional_word(env, &skip)?;
        let count = env.get_array_length(&candidates).ok()? as usize;
        let mut seed_counts = vec![0i32; count];
        env.get_int_array_region(&seeds, 0, &mut seed_counts).ok()?;
        let mut recency = vec![0u8; count];
        env.get_boolean_array_region(&recent, 0, &mut recency).ok()?;
        let mut lm_scores = vec![f32::NAN; count];
        if !lm.is_null() {
            env.get_float_array_region(&lm, 0, &mut lm_scores).ok()?;
        }
        let mut list = Vec::with_capacity(count);
        for i in 0..count {
            let item = JString::from(env.get_object_array_element(&candidates, i as i32).ok()?);
            let w = word(env, &item)?;
            env.delete_local_ref(item).ok()?;
            list.push(Candidate { word: w, seed: seed_counts[i], recent: recency[i] != 0, lm: lm_scores[i] });
        }
        let packs: Vec<&Pack<Mmap>> = held.iter().map(Arc::as_ref).collect();
        let context = Context { prev: &prev, prev2: prev2.as_ref(), prev3: prev3.as_ref(), skip: skip.as_ref() };
        let lexicon = store.lexicon.read().unwrap_or_else(|e| e.into_inner());
        let weight = if lm_weight > 0.0 { lm_weight } else { rerank::LM_WEIGHT };
        match rerank::rerank_weighted(&lexicon, &packs, &context, &list, weight) {
            None => Some(null_mut()),
            Some(order) => {
                let order: Vec<i32> = order.into_iter().map(|i| i as i32).collect();
                give_ints(env, &order).map(|a| a.into_raw())
            }
        }
    })
}
