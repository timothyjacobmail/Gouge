use jni::{JNIEnv, objects::{JByteArray, JClass}, sys::jbyteArray};
use pbkdf2::pbkdf2_hmac;
use sha2::Sha256;
use zeroize::Zeroizing;

// JNI owns all input arrays. Copy them through checked JNI methods, derive one fixed-size key,
// wipe our password/output copies, and return a new Java array. No global state or stored secrets.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_installlock_NativePasswordKdf_derive(
    mut env: JNIEnv,
    _class: JClass,
    password: JByteArray,
    salt: JByteArray,
) -> jbyteArray {
    let result = (|| -> jni::errors::Result<jbyteArray> {
        let password = Zeroizing::new(env.convert_byte_array(&password)?);
        let salt = env.convert_byte_array(&salt)?;
        let mut output = Zeroizing::new([0_u8; 32]);
        pbkdf2_hmac::<Sha256>(&password, &salt, 120_000, output.as_mut());
        Ok(env.byte_array_from_slice(output.as_ref())?.into_raw())
    })();
    match result {
        Ok(array) => array,
        Err(_) => {
            if !env.exception_check().unwrap_or(true) {
                let _ = env.throw_new("java/lang/IllegalStateException", "Password derivation failed");
            }
            std::ptr::null_mut()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn published_sha256_vector() {
        let mut result = [0; 32];
        pbkdf2_hmac::<Sha256>(b"password", b"salt", 1, &mut result);
        assert_eq!(result, [0x12,0x0f,0xb6,0xcf,0xfc,0xf8,0xb3,0x2c,0x43,0xe7,0x22,0x52,0x56,0xc4,0xf8,0x37,
            0xa8,0x65,0x48,0xc9,0x2c,0xcc,0x35,0x48,0x08,0x05,0x98,0x7c,0xb7,0x0b,0xe1,0x7b]);
    }
}
