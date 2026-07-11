// Per test-file setup: point DATABASE_URL at the shared test database
// *before* anything imports `@/lib/db` (which constructs the PrismaClient at
// module load time).
import { TEST_DATABASE_URL } from "./globalSetup";

process.env.DATABASE_URL = TEST_DATABASE_URL;
