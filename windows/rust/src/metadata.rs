use std::io::Cursor;

use mp4ameta::{Data, Fourcc, Img, ImgFmt, Tag};

pub fn read_m4a_tag(data: Vec<u8>) -> Option<Tag> {
    Tag::read_from(&mut Cursor::new(data)).ok()
}

pub fn set_m4a_string_tag(tag: &mut Tag, ident: [u8; 4], data: String) {
    tag.set_data(Fourcc(ident), Data::Utf8(data));
}

pub fn set_m4a_reserved_tag(tag: &mut Tag, ident: [u8; 4], data: Vec<u8>) {
    tag.set_data(Fourcc(ident), Data::Reserved(data));
}

pub fn set_m4a_artwork(tag: &mut Tag, bytes: &[u8]) {
    let fmt = if bytes.starts_with(b"\x89PNG") { ImgFmt::Png } else { ImgFmt::Jpeg };
    tag.set_artwork(Img::new(fmt, bytes.to_vec()));
}

pub fn set_m4a_disc(tag: &mut Tag, disc_number: u16, total_discs: u16) {
    tag.set_disc(disc_number, total_discs);
}

pub fn set_m4a_track(tag: &mut Tag, track_number: u16, total_tracks: u16) {
    tag.set_track(track_number, total_tracks);
}

pub fn set_m4a_compilation(tag: &mut Tag) {
    tag.set_compilation();
}

pub fn set_m4a_isrc(tag: &mut Tag, isrc: String) {
    tag.set_isrc(isrc);
}

pub fn write_m4a_tag(tag: &mut Tag, buffer: &mut Cursor<Vec<u8>>) -> bool {
    tag.write_to(buffer).is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn m4a_metadata_setters_update_expected_fields() {
        let mut tag = Tag::default();
        set_m4a_string_tag(&mut tag, *b"\xa9nam", "AMDL native test".to_owned());
        set_m4a_track(&mut tag, 3, 12);
        set_m4a_disc(&mut tag, 1, 2);
        set_m4a_isrc(&mut tag, "TEST00000001".to_owned());
        set_m4a_compilation(&mut tag);
        set_m4a_artwork(&mut tag, b"\x89PNG\r\n\x1a\n");

        assert_eq!(tag.title(), Some("AMDL native test"));
        assert_eq!(tag.track(), (Some(3), Some(12)));
        assert_eq!(tag.disc(), (Some(1), Some(2)));
        assert_eq!(tag.isrc(), Some("TEST00000001"));
        assert!(tag.compilation());
        assert_eq!(tag.artwork().map(|image| image.fmt), Some(ImgFmt::Png));
    }

    #[test]
    fn invalid_m4a_data_is_rejected_without_panicking() {
        assert!(read_m4a_tag(vec![0, 1, 2, 3]).is_none());

        let mut tag = Tag::default();
        let mut buffer = Cursor::new(Vec::new());
        assert!(!write_m4a_tag(&mut tag, &mut buffer));
    }
}
