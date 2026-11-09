use super::*;

const TRACK_ID: u32 = 1;
const SAMPLE_RATE: u32 = 44_100;
const SAMPLE_DURATION: u32 = 1024;

// NIST SP 800-38A, F.2.1: AES-128-CBC encryption block test vector.
const NIST_CBC_KEY: [u8; 16] = [
    0x2b, 0x7e, 0x15, 0x16, 0x28, 0xae, 0xd2, 0xa6, 0xab, 0xf7, 0x15, 0x88, 0x09, 0xcf, 0x4f,
    0x3c,
];
const NIST_CBC_IV: [u8; 16] = [
    0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e,
    0x0f,
];
const NIST_CBC_PLAINTEXT: [u8; 64] = [
    0x6b, 0xc1, 0xbe, 0xe2, 0x2e, 0x40, 0x9f, 0x96, 0xe9, 0x3d, 0x7e, 0x11, 0x73, 0x93, 0x17,
    0x2a, 0xae, 0x2d, 0x8a, 0x57, 0x1e, 0x03, 0xac, 0x9c, 0x9e, 0xb7, 0x6f, 0xac, 0x45, 0xaf,
    0x8e, 0x51, 0x30, 0xc8, 0x1c, 0x46, 0xa3, 0x5c, 0xe4, 0x11, 0xe5, 0xfb, 0xc1, 0x19, 0x1a,
    0x0a, 0x52, 0xef, 0xf6, 0x9f, 0x24, 0x45, 0xdf, 0x4f, 0x9b, 0x17, 0xad, 0x2b, 0x41, 0x7b,
    0xe6, 0x6c, 0x37, 0x10,
];
const NIST_CBC_CIPHERTEXT: [u8; 64] = [
    0x76, 0x49, 0xab, 0xac, 0x81, 0x19, 0xb2, 0x46, 0xce, 0xe9, 0x8e, 0x9b, 0x12, 0xe9, 0x19,
    0x7d, 0x50, 0x86, 0xcb, 0x9b, 0x50, 0x72, 0x19, 0xee, 0x95, 0xdb, 0x11, 0x3a, 0x91, 0x76,
    0x78, 0xb2, 0x73, 0xbe, 0xd6, 0xb8, 0xe3, 0xc1, 0x74, 0x3b, 0x71, 0x16, 0xe6, 0x9e, 0x22,
    0x22, 0x95, 0x16, 0x3f, 0xf1, 0xca, 0xa1, 0x68, 0x1f, 0xac, 0x09, 0x12, 0x0e, 0xca, 0x30,
    0x75, 0x86, 0xe1, 0xa7,
];

// AES-128-CBC(NIST_CBC_PLAINTEXT, DEFAULT_SONG_DECRYPTION_KEY, NIST_CBC_IV).
const LEGACY_DEFAULT_CIPHERTEXT: [u8; 64] = [
    0x6a, 0xb9, 0x7d, 0x90, 0x19, 0xba, 0xc2, 0x4e, 0x94, 0x3a, 0xfb, 0xf9, 0xfc, 0xa8, 0xd6,
    0x41, 0x40, 0x32, 0xcf, 0x5f, 0x6c, 0x84, 0xc6, 0x46, 0x67, 0xe8, 0x42, 0x46, 0xa3, 0x6b,
    0x28, 0x00, 0x6d, 0x99, 0xf1, 0x1d, 0xdc, 0xfa, 0xb3, 0x40, 0x5a, 0x2b, 0x65, 0x73, 0x61,
    0x55, 0x52, 0x38, 0x90, 0x07, 0xd9, 0xa9, 0x8d, 0x0d, 0xbc, 0xbc, 0x3e, 0x98, 0x74, 0x20,
    0xc6, 0x79, 0xda, 0xc0,
];

#[derive(Clone, Copy)]
struct TencSpec {
    is_protected: bool,
    constant_iv: [u8; 16],
}

struct SampleFixture {
    payload: Vec<u8>,
    description_index: Option<u32>,
}

fn fixture_box(typ: &[u8; 4], content: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    push_box(&mut out, typ, content).unwrap();
    out
}

fn fixture_full_box(typ: &[u8; 4], version: u8, flags: u32, content: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    push_full_box(&mut out, typ, version, flags, content).unwrap();
    out
}

fn encrypted_audio_entry(spec: TencSpec) -> Vec<u8> {
    let mut content = vec![0; 6];
    put_u16(&mut content, 1); // data_reference_index
    put_u16(&mut content, 0); // version
    put_u16(&mut content, 0); // revision
    put_u32(&mut content, 0); // vendor
    put_u16(&mut content, 2); // channel count
    put_u16(&mut content, 16); // sample size
    put_u16(&mut content, 0); // compression id
    put_u16(&mut content, 0); // packet size
    put_u32(&mut content, SAMPLE_RATE << 16);

    let frma = fixture_box(b"frma", b"mp4a");
    let mut schm_content = b"cbcs".to_vec();
    put_u32(&mut schm_content, 0x0001_0000);
    let schm = fixture_full_box(b"schm", 0, 0, &schm_content);

    let mut tenc_content = vec![0]; // reserved
    tenc_content.push(0); // crypt/skip pattern: full CBC
    tenc_content.push(u8::from(spec.is_protected));
    tenc_content.push(0); // no per-sample IV
    tenc_content.extend_from_slice(&[0; 16]); // default KID
    tenc_content.push(16); // constant IV size
    tenc_content.extend_from_slice(&spec.constant_iv);
    let tenc = fixture_full_box(b"tenc", 1, 0, &tenc_content);
    let schi = fixture_box(b"schi", &tenc);

    let mut sinf_content = frma;
    sinf_content.extend_from_slice(&schm);
    sinf_content.extend_from_slice(&schi);
    content.extend_from_slice(&fixture_box(b"sinf", &sinf_content));
    fixture_box(b"enca", &content)
}

fn fixture_moov(descriptions: &[TencSpec], trex_description_index: u32) -> Vec<u8> {
    let mut stsd_content = Vec::new();
    put_u32(&mut stsd_content, descriptions.len() as u32);
    for &description in descriptions {
        stsd_content.extend_from_slice(&encrypted_audio_entry(description));
    }
    let stsd = fixture_full_box(b"stsd", 0, 0, &stsd_content);
    let stbl = fixture_box(b"stbl", &stsd);
    let minf = fixture_box(b"minf", &stbl);

    let mut mdia_content = build_mdhd(SAMPLE_DURATION as u64, SAMPLE_RATE).unwrap();
    mdia_content.extend_from_slice(&build_hdlr().unwrap());
    mdia_content.extend_from_slice(&minf);
    let mdia = fixture_box(b"mdia", &mdia_content);

    let mut trak_content = build_tkhd(SAMPLE_DURATION as u64).unwrap();
    trak_content.extend_from_slice(&mdia);
    let trak = fixture_box(b"trak", &trak_content);

    let mut trex_content = Vec::new();
    put_u32(&mut trex_content, TRACK_ID);
    put_u32(&mut trex_content, trex_description_index);
    put_u32(&mut trex_content, SAMPLE_DURATION);
    put_u32(&mut trex_content, 0);
    put_u32(&mut trex_content, 0);
    let trex = fixture_full_box(b"trex", 0, 0, &trex_content);
    let mvex = fixture_box(b"mvex", &trex);

    let mut moov_content = build_mvhd(SAMPLE_DURATION as u64, SAMPLE_RATE).unwrap();
    moov_content.extend_from_slice(&trak);
    moov_content.extend_from_slice(&mvex);
    fixture_box(b"moov", &moov_content)
}

fn fixture_tfhd(sample_size: usize, description_index: Option<u32>) -> Vec<u8> {
    let mut flags = 0x020018; // default-base-is-moof, default duration/size, sample flags
    if description_index.is_some() {
        flags |= 0x02;
    }

    let mut content = Vec::new();
    put_u32(&mut content, TRACK_ID);
    if let Some(index) = description_index {
        put_u32(&mut content, index);
    }
    put_u32(&mut content, SAMPLE_DURATION);
    put_u32(&mut content, sample_size as u32);
    put_u32(&mut content, 0);
    fixture_full_box(b"tfhd", 0, flags, &content)
}

fn fixture_trun() -> Vec<u8> {
    let mut content = Vec::new();
    put_u32(&mut content, 1);
    fixture_full_box(b"trun", 0, 0, &content)
}

fn fragmented_source(
    descriptions: &[TencSpec],
    trex_description_index: u32,
    samples: &[SampleFixture],
) -> Vec<u8> {
    let mut source = ftyp_m4a().unwrap();
    source.extend_from_slice(&fixture_moov(descriptions, trex_description_index));

    for (sequence_number, sample) in samples.iter().enumerate() {
        let mut mfhd_content = Vec::new();
        put_u32(&mut mfhd_content, sequence_number as u32 + 1);
        let mfhd = fixture_full_box(b"mfhd", 0, 0, &mfhd_content);

        let mut traf_content = fixture_tfhd(sample.payload.len(), sample.description_index);
        traf_content.extend_from_slice(&fixture_trun());
        let traf = fixture_box(b"traf", &traf_content);

        let mut moof_content = mfhd;
        moof_content.extend_from_slice(&traf);
        source.extend_from_slice(&fixture_box(b"moof", &moof_content));
        source.extend_from_slice(&fixture_box(b"mdat", &sample.payload));
    }

    source
}

fn output_mdat_payload(output: &[u8]) -> &[u8] {
    let boxes = scan_top_level_boxes(output).unwrap();
    let mdat = boxes
        .iter()
        .find(|item| &item.typ == b"mdat")
        .expect("decrypted M4A must contain mdat");
    &output[mdat.offset + mdat.header_size..mdat.offset + mdat.size]
}

#[test]
fn single_cbcs_description_uses_track_key_and_constant_iv() {
    let descriptions = [TencSpec {
        is_protected: true,
        constant_iv: NIST_CBC_IV,
    }];
    let samples = [SampleFixture {
        payload: NIST_CBC_CIPHERTEXT.to_vec(),
        description_index: None,
    }];
    let source = fragmented_source(&descriptions, 1, &samples);

    let output = decrypt_m4a(&NIST_CBC_KEY, source).unwrap();

    assert_eq!(output_mdat_payload(&output), NIST_CBC_PLAINTEXT);
}

#[test]
fn legacy_two_descriptions_keep_index_zero_default_and_index_one_track_key() {
    let descriptions = [
        TencSpec {
            is_protected: true,
            constant_iv: NIST_CBC_IV,
        },
        TencSpec {
            is_protected: true,
            constant_iv: NIST_CBC_IV,
        },
    ];
    let samples = [
        SampleFixture {
            payload: LEGACY_DEFAULT_CIPHERTEXT.to_vec(),
            description_index: Some(1),
        },
        SampleFixture {
            payload: NIST_CBC_CIPHERTEXT.to_vec(),
            description_index: Some(2),
        },
    ];
    let source = fragmented_source(&descriptions, 1, &samples);

    let output = decrypt_m4a(&NIST_CBC_KEY, source).unwrap();

    let mut expected = NIST_CBC_PLAINTEXT.to_vec();
    expected.extend_from_slice(&NIST_CBC_PLAINTEXT);
    assert_eq!(output_mdat_payload(&output), expected);
}

#[test]
fn trex_default_description_two_uses_track_key_when_tfhd_omits_it() {
    let descriptions = [
        TencSpec {
            is_protected: true,
            constant_iv: NIST_CBC_IV,
        },
        TencSpec {
            is_protected: true,
            constant_iv: NIST_CBC_IV,
        },
    ];
    let samples = [SampleFixture {
        payload: NIST_CBC_CIPHERTEXT.to_vec(),
        description_index: None,
    }];
    let source = fragmented_source(&descriptions, 2, &samples);

    let output = decrypt_m4a(&NIST_CBC_KEY, source).unwrap();

    assert_eq!(output_mdat_payload(&output), NIST_CBC_PLAINTEXT);
}

#[test]
fn unprotected_description_leaves_sample_payload_untouched() {
    let descriptions = [TencSpec {
        is_protected: false,
        constant_iv: NIST_CBC_IV,
    }];
    let payload: Vec<u8> = (0..80).map(|value| value as u8 ^ 0xa5).collect();
    let samples = [SampleFixture {
        payload: payload.clone(),
        description_index: None,
    }];
    let source = fragmented_source(&descriptions, 1, &samples);

    let output = decrypt_m4a(&NIST_CBC_KEY, source).unwrap();

    assert_eq!(output_mdat_payload(&output), payload);
}

#[test]
fn out_of_range_description_is_rejected() {
    let descriptions = [
        TencSpec {
            is_protected: true,
            constant_iv: NIST_CBC_IV,
        },
        TencSpec {
            is_protected: true,
            constant_iv: NIST_CBC_IV,
        },
    ];
    let samples = [SampleFixture {
        payload: NIST_CBC_CIPHERTEXT.to_vec(),
        description_index: Some(3),
    }];
    let source = fragmented_source(&descriptions, 1, &samples);

    let error = decrypt_m4a(&NIST_CBC_KEY, source).unwrap_err();

    assert_eq!(error.kind(), io::ErrorKind::InvalidData);
    assert_eq!(
        error.to_string(),
        "decrypt: invalid sample description index"
    );
}
