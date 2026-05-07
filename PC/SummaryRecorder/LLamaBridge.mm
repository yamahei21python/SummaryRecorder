#import "LLamaBridge.h"
#import "llama.h"

#include <string>
#include <vector>
#include <atomic>

// Simplified GBNF grammar for JSON output (Japanese-safe)
// No UTF-8 range hacks — char accepts any byte except " \ and control chars
static const char *GRAMMAR_JSON = R"(
root ::= object
object ::= "{" space "\"title\"" space ":" space string space "," space "\"summaryText\"" space ":" space string space "}"
string ::= "\"" char* "\""
char ::= [^"\\] | "\\" escape
escape ::= ["\\/bfnrt] | "u" [0-9a-fA-F]{4}
space ::= [ \t\n\r]*
)";

@implementation LLamaBridgeObjC {
    struct llama_model *_model;
    struct llama_context *_ctx;
    struct llama_sampler *_sampler;
    std::atomic<bool> _isCancelled;
}

- (nullable instancetype)initWithModelPath:(NSString *)modelPath error:(NSError **)error {
    self = [super init];
    if (self) {
        _model = nullptr;
        _ctx = nullptr;
        _sampler = nullptr;
        _isCancelled = false;

        llama_model_params mparams = llama_model_default_params();
        _model = llama_model_load_from_file([modelPath UTF8String], mparams);
        if (!_model) {
            if (error) {
                *error = [NSError errorWithDomain:@"LLamaBridge" code:1
                                            userInfo:@{NSLocalizedDescriptionKey: @"Failed to load llama model"}];
            }
            return nil;
        }

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = 2048;
        cparams.n_threads = (int)[[NSProcessInfo processInfo] processorCount];
        cparams.n_threads_batch = cparams.n_threads;

        _ctx = llama_init_from_model(_model, cparams);
        if (!_ctx) {
            llama_model_free(_model);
            _model = nullptr;
            if (error) {
                *error = [NSError errorWithDomain:@"LLamaBridge" code:2
                                            userInfo:@{NSLocalizedDescriptionKey: @"Failed to create context"}];
            }
            return nil;
        }

        // Initialize sampler chain: top_k -> top_p -> temp
        struct llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
        _sampler = llama_sampler_chain_init(sparams);
        llama_sampler_chain_add(_sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(_sampler, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(_sampler, llama_sampler_init_temp(0.6f));
    }
    return self;
}

- (void)dealloc {
    if (_sampler) llama_sampler_free(_sampler);
    if (_ctx) llama_free(_ctx);
    if (_model) llama_model_free(_model);
}

- (void)summarize:(NSString *)text
     systemPrompt:(NSString *)systemPrompt
       completion:(void (^)(NSString * _Nullable, NSError * _Nullable))completion {
    if (!_ctx) {
        completion(nil, [NSError errorWithDomain:@"LLamaBridge" code:3
                                    userInfo:@{NSLocalizedDescriptionKey: @"Model not loaded"}]);
        return;
    }

    _isCancelled = false;

    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        __block bool cancelled = false;
        const struct llama_vocab *vocab = llama_model_get_vocab(self->_model);
        std::string prompt = std::string([systemPrompt UTF8String]) + "\n\n" + std::string([text UTF8String]);

        // Tokenize prompt
        std::vector<llama_token> tokens(prompt.size() + 8);
        int32_t n_tokens = llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(),
                                          tokens.data(), (int32_t)tokens.size(), true, false);
        if (n_tokens < 0) {
            dispatch_async(dispatch_get_main_queue(), ^{
                completion(nil, [NSError errorWithDomain:@"LLamaBridge" code:6
                                                    userInfo:@{NSLocalizedDescriptionKey: @"Tokenization failed"}]);
            });
            return;
        }
        tokens.resize(n_tokens);

        // Create batch for prompt tokens and decode
        struct llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
        if (llama_decode(self->_ctx, batch) != 0) {
            dispatch_async(dispatch_get_main_queue(), ^{
                completion(nil, [NSError errorWithDomain:@"LLamaBridge" code:4
                                                    userInfo:@{NSLocalizedDescriptionKey: @"Prompt decode failed"}]);
            });
            return;
        }

        // Build sampler: top_k -> top_p -> temp -> dist
        // NOTE: Grammar disabled for Gemma 4 (tokenizer incompatible with GBNF).
        // JSON format enforced via prompt + post-parse instead.
        struct llama_sampler_chain_params gcparams = llama_sampler_chain_default_params();
        struct llama_sampler *genSampler = llama_sampler_chain_init(gcparams);
        llama_sampler_chain_add(genSampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(genSampler, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(genSampler, llama_sampler_init_temp(0.6f));
        llama_sampler_chain_add(genSampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

        // Generate tokens
        std::string result;
        const int maxTokens = 1024;
        for (int i = 0; i < maxTokens; i++) {
            if (self->_isCancelled.load()) {
                cancelled = true;
                break;
            }

            llama_token newToken = llama_sampler_sample(genSampler, self->_ctx, -1);

            if (newToken == llama_vocab_eos(vocab)) break;

            // Accept token into sampler (for grammar tracking)
            llama_sampler_accept(genSampler, newToken);

            // Convert token to text
            char buf[256];
            int n = llama_token_to_piece(vocab, newToken, buf, sizeof(buf), 0, true);
            if (n > 0) {
                result.append(buf, n);
            }

            // Create batch for single token and decode
            struct llama_batch decodeBatch = llama_batch_get_one(&newToken, 1);
            if (llama_decode(self->_ctx, decodeBatch) != 0) break;
        }

        llama_sampler_free(genSampler);

        dispatch_async(dispatch_get_main_queue(), ^{
            if (cancelled) {
                completion(nil, [NSError errorWithDomain:@"LLamaBridge" code:5
                                                    userInfo:@{NSLocalizedDescriptionKey: @"Cancelled"}]);
            } else {
                NSString *summary = result.empty() ? nil : @(result.c_str());
                completion(summary, nil);
            }
        });
    });
}

- (void)cancel {
    _isCancelled = true;
}

@end
