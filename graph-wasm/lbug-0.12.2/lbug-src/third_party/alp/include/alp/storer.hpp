#ifndef ALP_API_MEM_STORER_HPP
#define ALP_API_MEM_STORER_HPP

#include <cstdint>
#include <string.h>

namespace alp { namespace storer {

template <bool DRY = false>
struct MemStorer {

	uint8_t* out_buffer;
	size_t   buffer_offset;

	MemStorer() {}
	MemStorer(uint8_t* out_buffer)
	    : out_buffer(out_buffer)
	    , buffer_offset(0) {}

	void set_buffer(uint8_t* out) { out_buffer = out; }

	void reset() { buffer_offset = 0; }

	size_t get_size() { return buffer_offset; }

	void store(void* in, size_t bytes_to_store) {
		if (!DRY) memcpy((void*)(out_buffer + buffer_offset), in, bytes_to_store);
		buffer_offset += bytes_to_store;
	}
};

struct MemReader {

	uint8_t* in_buffer;
	size_t   buffer_offset;

	MemReader() {}
	MemReader(uint8_t* in_buffer)
	    : in_buffer(in_buffer)
	    , buffer_offset(0) {}

	void set_buffer(uint8_t* in) { in_buffer = in; }

	void reset() { buffer_offset = 0; }

	size_t get_size() { return buffer_offset; }

	void read(void* out, size_t bytes_to_read) {
		memcpy(out, (void*)(in_buffer + buffer_offset), bytes_to_read);
		buffer_offset += bytes_to_read;
	}
};

}} // namespace alp::storer

#endif